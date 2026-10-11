package com.bilimusic.desktop.player

import com.bilimusic.app.data.player.StreamUrlRepository
import com.bilimusic.app.data.remote.BiliClient
import com.bilimusic.app.domain.model.PlayMode
import com.bilimusic.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 队列里的一首歌（桌面端用的轻量模型） */
data class Track(
    val bvid: String,
    val cid: Long,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    /** 非空表示这是本地文件（直接交给 VLC 播，不走 B 站取流） */
    val localPath: String? = null,
) {
    val isLocal: Boolean get() = !localPath.isNullOrBlank()
}

data class PlaybackUiState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val mode: PlayMode = PlayMode.DEFAULT,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 80,
    val qualityLabel: String = "",
    val message: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/**
 * 桌面端播放控制（FR-4）：队列、4 种播放模式、切歌、进度、音量。
 *
 * 与安卓端语义保持一致：
 * - 顺序播放 = 播完最后一首停止
 * - 列表循环 = 播完回到第一首
 * - 单曲循环 = 反复播当前这首
 * - 随机播放 = 打乱一次顺序后按该顺序播（播完即停）
 */
class PlaybackController(
    private val client: BiliClient,
    private val streamUrls: StreamUrlRepository,
    private val scope: CoroutineScope,
    private val player: VlcPlayer = VlcPlayer(),
) {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** 随机模式的播放顺序（索引序列） */
    private var shuffleOrder: List<Int> = emptyList()

    /** 当前这首歌对应的 bvid/cid，用于失效重取 */
    private var currentKey: Pair<String, Long>? = null

    private companion object {
        const val TAG = "DesktopPlayback"
        /** 进度回写节流：VLC 每秒回调多次，太频繁会让 UI 抖动 */
        const val TICK_MS = 500L
    }

    init {
        player.onStateChanged = { s ->
            _state.value = _state.value.copy(
                playing = s.playing,
                positionMs = s.positionMs,
                durationMs = if (s.durationMs > 0) s.durationMs else _state.value.durationMs,
                volume = s.volume,
                message = s.error ?: _state.value.message,
            )
        }
        player.onFinished = {
            // VLC 事件线程 → 切到协程里做切歌
            scope.launch { onTrackFinished() }
        }
    }

    // ---------------- 队列 ----------------

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        shuffleOrder = if (_state.value.mode == PlayMode.SHUFFLE) {
            tracks.indices.shuffled()
        } else {
            tracks.indices.toList()
        }
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        _state.value = _state.value.copy(queue = tracks, index = safeIndex, positionMs = 0L, durationMs = 0L)
        playCurrent()
    }

    /** 把 BV 号 / 链接解析成一首歌并立即播放（播放页的「马上试听」入口） */
    fun playSingle(track: Track) = playQueue(listOf(track), 0)

    fun playAt(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.value = _state.value.copy(index = index, positionMs = 0L, durationMs = 0L)
        playCurrent()
    }

    private fun playCurrent() {
        val track = _state.value.current ?: return
        currentKey = track.bvid to track.cid
        player.resetForNewTrack()

        // 本地文件：直接交给 VLC 播，不需要取流
        track.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            _state.value = _state.value.copy(
                loading = false,
                message = null,
                qualityLabel = "本地",
                durationMs = track.durationMs,
            )
            scope.launch { withContext(Dispatchers.IO) { player.play(java.io.File(path).toURI().toString()) } }
            return
        }

        scope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            val stream = withContext(Dispatchers.IO) { streamUrls.resolve(track.bvid, track.cid) }
            if (stream == null) {
                _state.value = _state.value.copy(loading = false, message = "取流失败：${track.title}")
                return@launch
            }
            _state.value = _state.value.copy(
                loading = false,
                qualityLabel = stream.displayLabel,
                durationMs = if (track.durationMs > 0) track.durationMs else 0L,
            )
            withContext(Dispatchers.IO) { player.play(stream.url) }
        }
    }

    // ---------------- 控制 ----------------

    fun togglePlayPause() {
        if (_state.value.playing) player.pause() else player.resume()
    }

    fun next() {
        scope.launch { advance(manual = true) }
    }

    fun previous() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val index = orderOf(_state.value.index)
        val prevIndex = if (index <= 0) queue.lastIndex else index - 1
        playAt(_orderedIndex(prevIndex))
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0L, _state.value.durationMs))
    }

    fun setVolume(volume: Int) = player.setVolume(volume)

    fun cycleMode() {
        val next = when (_state.value.mode) {
            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
        }
        setMode(next)
    }

    fun setMode(mode: PlayMode) {
        _state.value = _state.value.copy(mode = mode, message = "播放模式：${mode.displayName}")
        shuffleOrder = if (mode == PlayMode.SHUFFLE) {
            _state.value.queue.indices.shuffled()
        } else {
            _state.value.queue.indices.toList()
        }
    }

    /** 取流地址过期 / 播放失败：清缓存重取一次 */
    fun retryCurrent() {
        val key = currentKey ?: return
        streamUrls.invalidate(key.first, key.second)
        playCurrent()
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** 播放页状态里剩下的错误（例如地址失效）由上层提示 */
    fun reportError(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    fun release() {
        player.release()
    }

    // ---------------- 内部：顺序与自动切歌 ----------------

    /** 队列索引 → 播放顺序里的位置 */
    private fun orderOf(queueIndex: Int): Int {
        val pos = shuffleOrder.indexOf(queueIndex)
        return if (pos >= 0) pos else queueIndex
    }

    /** 播放顺序里的位置 → 队列索引 */
    private fun _orderedIndex(orderPos: Int): Int {
        if (shuffleOrder.isEmpty()) return orderPos
        val size = shuffleOrder.size
        return shuffleOrder[((orderPos % size) + size) % size]
    }

    private suspend fun onTrackFinished() {
        when (_state.value.mode) {
            PlayMode.REPEAT_ONE -> playCurrent()
            else -> advance(manual = false)
        }
    }

    private suspend fun advance(manual: Boolean) {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val size = shuffleOrder.size.coerceAtLeast(1)
        val currentOrderPos = orderOf(_state.value.index)
        val nextOrderPos = currentOrderPos + 1

        when {
            nextOrderPos < size -> playAt(_orderedIndex(nextOrderPos))

            _state.value.mode == PlayMode.REPEAT_ALL -> {
                AppLog.i(TAG, "列表循环：回到第一首")
                playAt(_orderedIndex(0))
            }

            else -> {
                // 顺序/随机播完最后一首：停止（与安卓端一致）
                AppLog.i(TAG, "队列播放结束，停止")
                player.stop()
                _state.value = _state.value.copy(playing = false, message = "歌单播放完了")
            }
        }
    }
}
