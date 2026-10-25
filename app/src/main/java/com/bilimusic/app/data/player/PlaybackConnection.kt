package com.bilimusic.app.data.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.glance.appwidget.updateAll
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bilimusic.app.data.local.prefs.PlaybackPrefs
import com.bilimusic.app.domain.model.AudioQualityOption
import com.bilimusic.app.domain.model.PlayMode
import com.bilimusic.app.domain.model.Song
import com.bilimusic.app.domain.model.formatDuration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** 播放页 / 迷你播放条共用的播放状态（唯一真源是服务里的 ExoPlayer） */
data class PlaybackUiState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val bvid: String = "",
    val cid: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentIndex: Int = 0,
    val queueSize: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playMode: PlayMode = PlayMode.DEFAULT,
    val qualityLabel: String = "",
    /** 当前这首是不是本地文件（本地不取流、不缓存，UI 上显示「本地」而不是音质档位） */
    val isLocal: Boolean = false,
    val errorMessage: String? = null,
) {
    val indexText: String
        get() = if (queueSize > 0) "第 ${currentIndex + 1} 首 / 共 $queueSize 首" else "第 0 首 / 共 0 首"

    val positionText: String get() = formatDuration(positionMs)

    val durationText: String get() = formatDuration(durationMs)

    /** 0f..1f；时长未知时返回 0 */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** 队列里的一项（FR-4 队列页展示用） */
data class QueueItem(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isCurrent: Boolean,
    /** 合集/分P信息，例如「合集 · 第 3 集 / 共 20 集」 */
    val subtitle: String? = null,
)

/**
 * UI 与 [PlaybackService] 之间的唯一通道。
 *
 * 架构纪律（任务书第 5 节）：播放器的唯一真源是服务里的 ExoPlayer，
 * UI 通过 MediaController 连接，**不在 UI 层新建第二个 ExoPlayer**。
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackPrefs: PlaybackPrefs,
    private val streamUrlRepository: StreamUrlRepository,
    private val sleepTimer: SleepTimer,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** FR-4：当前**实际播放顺序**的队列快照（shuffle 时就是打乱后的顺序） */
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    /** FR-10：音质降级等一次性提示（UI 弹完 Snackbar 调 [consumeNotice] 清掉） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 已提示过的曲目，避免同一首反复弹 */
    private val notifiedDowngrades = mutableSetOf<String>()

    /**
     * FR-11：桌面小组件的 composition 不会自己常驻监听状态，
     * 所以播放状态有「实质性变化」时主动推一次更新（位置跳动不算，避免每秒刷新）。
     */
    private var lastWidgetSignature: String? = null

    private fun refreshWidgetIfNeeded(state: PlaybackUiState) {
        val signature = listOf(
            state.hasMedia,
            state.title,
            state.artist,
            state.isPlaying,
            state.currentIndex,
            state.queueSize,
            state.qualityLabel,
            state.playMode,
        ).joinToString("|")
        if (signature == lastWidgetSignature) return
        lastWidgetSignature = signature
        scope.launch {
            runCatching {
                com.bilimusic.app.widget.PlayerWidget().updateAll(context)
            }.onFailure { Log.w(TAG, "刷新桌面小组件失败", it) }
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** mediaKey(bvid-cid) → 数据库里的 songId，用于记录「上次播放」 */
    private val songIdByMediaKey = mutableMapOf<String, Long>()

    private var controller: MediaController? = null
    private var connecting = false
    private var tickerJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            rebuildQueue()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let { publish(it) }
            rebuildQueue()
            rememberLastPlayed(mediaItem)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "播放出错：${error.errorCodeName} ${error.message}")
            // 本地曲目常见「媒体库有条目但文件已被删除/损坏」：这种坏文件不该让整个队列停下，
            // 自动跳到下一首继续播（跳到队尾自然结束，不会无限循环）。
            val mediaController = controller
            val hasNext = mediaController != null &&
                mediaController.currentMediaItemIndex < mediaController.mediaItemCount - 1
            if (hasNext) {
                Log.w(TAG, "这一首打不开，自动跳到下一首")
                mediaController.seekToNextMediaItem()
                mediaController.play()
                return
            }
            _state.value = _state.value.copy(errorMessage = describeError(error))
        }
    }

    /** 连接服务（幂等）。App 启动或进播放页时调用。 */
    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                connecting = false
                val mediaController = runCatching { future.get() }.getOrNull()
                if (mediaController == null) {
                    Log.w(TAG, "MediaController 连接失败")
                    return@addListener
                }
                controller = mediaController
                mediaController.addListener(listener)
                // 恢复上次保存的播放模式（首次安装是「列表循环」），让 UI 一进来就显示正确的模式
                scope.launch {
                    applyPlayMode(
                        mediaController,
                        PlayMode.fromNameOrRepeatAll(playbackPrefs.playMode.first()),
                    )
                }
                publish(mediaController)
                startTicker()
                Log.i(TAG, "已连接到 PlaybackService")
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * 用整个歌单建队列并从 startIndex 开始播。
     *
     * 播放模式的处理：
     * - `shuffle = true`（歌单页的「随机播放」按钮）→ 强制切到随机，并记住
     * - `shuffle = false`（播放全部 / 点某一首 / 选集播放）→ **沿用当前保存的模式**，
     *   不再像以前那样硬写成「顺序播放」——否则用户选了列表循环，一点播放全部就被改回去了
     */
    fun playSongs(
        songs: List<Song>,
        startIndex: Int,
        shuffle: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        connect()
        // 用户主动开始新播放：清掉上次遗留的「本曲播完后停止」（正在倒计时的定时不受影响）
        sleepTimer.onUserStartedNewPlayback()
        scope.launch {
            val mediaController = awaitController() ?: return@launch
            val qualitySetting = AudioQualityOption.fromNameOrAuto(
                playbackPrefs.audioQualityOption.first(),
            )
            for (song in songs) {
                songIdByMediaKey[song.mediaKey] = song.id
            }
            val items = songs.map { it.toMediaItem(qualitySetting) }
            val index = startIndex.coerceIn(0, items.lastIndex)
            mediaController.setMediaItems(items, index, 0L)
            // 洗牌顺序由服务端在创建播放器时用固定 seed 设好（MediaController 不支持 setShuffleOrder），
            // 这里只切开关，保证同一次会话内顺序稳定、不会每切一次歌就重排。
            val mode = if (shuffle) {
                PlayMode.SHUFFLE
            } else {
                PlayMode.fromNameOrRepeatAll(playbackPrefs.playMode.first())
            }
            applyPlayMode(mediaController, mode)
            mediaController.prepare()
            mediaController.play()
            playbackPrefs.setPlayMode(mode.name)
        }
    }

    /** 把播放模式同时应用到播放器与偏好（两处必须一致，UI 读的是播放器状态） */
    private suspend fun applyPlayMode(mediaController: MediaController, mode: PlayMode) {
        mediaController.shuffleModeEnabled = mode == PlayMode.SHUFFLE
        mediaController.repeatMode = when (mode) {
            // 随机播放是「无限随机」：一轮播完继续循环，不自动停止（与桌面端行为一致）
            PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL
            PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
            PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        }
    }

    fun togglePlayPause() {
        scope.launch {
            val mediaController = controller ?: return@launch
            if (mediaController.isPlaying) {
                mediaController.pause()
            } else {
                // FR-5：用户在「本曲播完后停止」状态下重新点播放 → 取消定时停止
                sleepTimer.cancelByUserResume()
                if (mediaController.playbackState == Player.STATE_IDLE) mediaController.prepare()
                mediaController.play()
            }
        }
    }

    fun next() {
        scope.launch { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    }

    fun previous() {
        scope.launch {
            controller?.let { player ->
                when {
                    player.currentPosition > 3_000L -> player.seekTo(0L)
                    player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
                    else -> player.seekTo(0L)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch { controller?.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    fun playItemAt(index: Int) {
        sleepTimer.onUserStartedNewPlayback()
        scope.launch {
            controller?.let { player ->
                if (index in 0 until player.mediaItemCount) {
                    player.seekTo(index, 0L)
                    if (!player.isPlaying) player.play()
                }
            }
        }
    }

    fun setPlayMode(mode: PlayMode) {
        scope.launch {
            // 先落盘：即使此刻没连上播放服务，用户的模式选择也不能丢
            playbackPrefs.setPlayMode(mode.name)
            val mediaController = controller
            if (mediaController == null) {
                // 服务刚断/还没连上：顺手重连，连上后 connect() 会应用保存的模式
                Log.i(TAG, "设置播放模式时未连接，先重连：${mode.displayName}")
                connect()
                _notice.value = "播放模式：${mode.displayName}"
                return@launch
            }
            applyPlayMode(mediaController, mode)
            // 图标比较接近，切完给一句明确反馈，避免"到底切没切"的疑惑
            _notice.value = "播放模式：${mode.displayName}"
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    // ---------------- FR-4 队列操作 ----------------

    /** 点击队列某一行 → 跳到该曲 */
    fun jumpToQueueItem(index: Int) {
        scope.launch {
            val mediaController = controller ?: return@launch
            if (index in 0 until mediaController.mediaItemCount) {
                mediaController.seekTo(index, 0L)
                if (!mediaController.isPlaying) mediaController.play()
            }
        }
    }

    /** 调整队列顺序（长按队列项 → 上移/下移） */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        scope.launch {
            val mediaController = controller ?: return@launch
            if (fromIndex in 0 until mediaController.mediaItemCount &&
                toIndex in 0 until mediaController.mediaItemCount &&
                fromIndex != toIndex
            ) {
                mediaController.moveMediaItem(fromIndex, toIndex)
            }
        }
    }

    /** 从队列移除某一项（左滑删除） */
    fun removeQueueItem(index: Int) {
        scope.launch {
            val mediaController = controller ?: return@launch
            if (index in 0 until mediaController.mediaItemCount) {
                mediaController.removeMediaItem(index)
            }
        }
    }

    /** 队列快照：顺序模式下就是原顺序，shuffle 模式下 getMediaItemAt(i) 就是打乱后的真实顺序 */
    fun currentQueue(): List<MediaItem> {
        val mediaController = controller ?: return emptyList()
        return (0 until mediaController.mediaItemCount).map { mediaController.getMediaItemAt(it) }
    }

    private fun rebuildQueue() {
        val mediaController = controller ?: return
        val currentIndex = mediaController.currentMediaItemIndex
        _queue.value = (0 until mediaController.mediaItemCount).map { index ->
            val item = mediaController.getMediaItemAt(index)
            QueueItem(
                index = index,
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                artworkUrl = item.mediaMetadata.artworkUri?.toString(),
                isCurrent = index == currentIndex,
                subtitle = item.mediaMetadata.subtitle?.toString(),
            )
        }
    }

    fun release() {
        tickerJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = PlaybackUiState()
    }

    /** 播放服务是否仍然连着（UI 可以用它判断「播放器还在不在」） */
    val isConnected: Boolean get() = controller?.isConnected == true

    // ---------------- 内部 ----------------

    private suspend fun awaitController(): MediaController? {
        repeat(30) {
            controller?.let { return it }
            delay(100)
        }
        return controller
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val mediaController = controller
                if (mediaController != null && !mediaController.isConnected) {
                    // 服务被系统回收或已停止：UI 不能继续显示「正在播放」的旧状态
                    Log.w(TAG, "MediaController 已断开，重置状态并尝试重连")
                    resetState()
                    // 不能 break：退出循环后即使重新连上也不会再刷新状态（UI 会冻住）。
                    // 也不能放着不管：断连后 UI 就再也控制不了播放了，所以这里主动重连，
                    // connect() 成功后会用新的 ticker 接替这个循环。
                    connect()
                    delay(TICK_MS)
                    continue
                }
                mediaController?.let { publish(it) }
                delay(TICK_MS)
            }
        }
    }

    /** 服务断开后把状态清干净，避免进度条冻在旧位置 */
    private fun resetState() {
        _state.value = PlaybackUiState()
        _queue.value = emptyList()
        controller?.removeListener(listener)
        runCatching { controller?.release() }
        controller = null
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val ref = item?.localConfiguration?.uri?.let { AudioUri.parse(it) }
        val cached = ref?.let { streamUrlRepository.cached(it.bvid, it.cid) }
        // 不是 bilimusic:// 虚拟地址的就是本地文件（content:// / file://）
        val localUri = item?.localConfiguration?.uri
        val isLocal = localUri != null && localUri.scheme != null && localUri.scheme != "bilimusic"
        val duration = player.duration
        _state.value = PlaybackUiState(
            connected = true,
            hasMedia = item != null,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            artworkUrl = metadata?.artworkUri?.toString(),
            bvid = ref?.bvid.orEmpty(),
            cid = ref?.cid ?: 0L,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            queueSize = player.mediaItemCount,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == C.TIME_UNSET || duration <= 0L) 0L else duration,
            playMode = playModeOf(player.repeatMode, player.shuffleModeEnabled),
            qualityLabel = cached?.displayLabel.orEmpty(),
            isLocal = isLocal,
            errorMessage = _state.value.errorMessage,
        )

        // FR-10：请求的音质接口没给（自动降级）→ 弹一次提示告诉用户实际在用哪一档
        if (cached != null && cached.downgraded && ref != null) {
            val key = "${ref.bvid}-${ref.cid}"
            if (notifiedDowngrades.add(key)) {
                Log.i(TAG, "音质降级提示：请求 ${cached.requested.displayName} → 实际 ${cached.displayLabel}")
                _notice.value =
                    "这首没有「${cached.requested.displayName}」音源，已自动降级为「${cached.displayLabel}」"
            }
        }

        // FR-11：桌面小组件跟着更新
        refreshWidgetIfNeeded(_state.value)
    }

    private fun playModeOf(repeatMode: Int, shuffleEnabled: Boolean): PlayMode = when {
        shuffleEnabled -> PlayMode.SHUFFLE
        repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
        repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
        else -> PlayMode.SEQUENTIAL
    }

    private fun rememberLastPlayed(mediaItem: MediaItem?) {
        val key = mediaItem?.mediaId ?: return
        val songId = songIdByMediaKey[key] ?: return
        scope.launch { playbackPrefs.setLastPlayedSongId(songId) }
    }

    private fun describeError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "音频地址已失效或没有权限（HTTP 错误），已尝试重新取流"

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "网络连接失败，请检查网络后重试"

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "音频流解析失败（可能是音质档位不受支持），请在设置里换个音质"

        else -> "播放失败：${error.errorCodeName}"
    }

    private companion object {
        const val TAG = "PlaybackConnection"
        const val TICK_MS = 500L
    }
}

/**
 * 曲目 → MediaItem。
 *
 * - B 站曲目：URI 用虚拟地址（播放时才解析真实取流地址），并写 cacheKey 让离线缓存能命中
 * - **本地曲目**：URI 直接就是 `content://…`，且**不设 cacheKey**（缓存层会跳过，不白占空间）
 */
private fun Song.toMediaItem(quality: AudioQualityOption): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(upperName)
        .setArtworkUri(coverUrl?.let { Uri.parse(it) })
        // 队列页要能看出「这是合集的哪几集」
        .setSubtitle(
            if (collectionKey != null && episodeCount > 1) {
                "合集 · 第 $pageIndex 集 / 共 $episodeCount 集"
            } else {
                null
            },
        )
        .build()

    val builder = MediaItem.Builder()
        .setMediaId(mediaKey)
        .setMediaMetadata(metadata)

    return if (isLocal) {
        builder.setUri(localUri).build()
    } else {
        builder
            .setUri(AudioUri.build(bvid, cid))
            .setCustomCacheKey("$bvid-$cid-${quality.qualityId ?: 0}")
            .build()
    }
}
