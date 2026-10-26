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
    /** 已下载的本地文件（有则离线播放，不再取流） */
    private val downloadedFile: (bvid: String, cid: Long) -> String? = { _, _ -> null },
    /** Settings store: remembers the volume (otherwise it resets to 80 on every launch). */
    private val settings: com.bilimusic.app.data.remote.KeyValueStore? = null,
) {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** 随机模式的播放顺序（索引序列） */
    private var shuffleOrder: List<Int> = emptyList()

    /** 当前这首歌对应的 bvid/cid，用于失效重取 */
    private var currentKey: Pair<String, Long>? = null

    /** 定时关闭：置位后当前曲播完就停 */
    private var stopAfterCurrentTrack = false

    /** 中断自动重连次数（换歌时重置，同一首最多重试 3 次） */
    private var interruptionRetries = 0

    /** 重新加载完成后要跳转到的位置（拖动进度条但当时没有活动媒体时记下来） */
    private var pendingSeekMs: Long? = null

    /**
     * 用户操作序号。兜底检查（resume 1.2s / seek 1.8s 后）会先比对这个序号：
     * 期间用户又点过任何按钮就放弃兜底 —— 否则「用户主动暂停」会被误判成
     * 「resume 失败」，反手把媒体重建掉（播放位置还会归零）。
     */
    @Volatile
    private var userActionSeq = 0

    private companion object {
        const val TAG = "DesktopPlayback"
        /** 进度回写节流：VLC 每秒回调多次，太频繁会让 UI 抖动 */
        const val TICK_MS = 500L
        /** Volume persistence key. */
        const val KEY_VOLUME = "desktop.volume"

        /** 同一首歌被中断后最多自动重连几次 */
        const val MAX_INTERRUPTION_RETRIES = 3
    }

    init {
        // 恢复上次音量：直接写 Windows 音频会话（VLC 自身音量固定满幅）
        runCatching {
            settings?.getString(KEY_VOLUME)?.toIntOrNull()?.coerceIn(0, 100)?.let { ui ->
                _state.value = _state.value.copy(volume = ui)
                player.setSessionVolume(uiToSessionVolume(ui))
            }
        }
        player.onStateChanged = { s ->
            _state.value = _state.value.copy(
                playing = s.playing,
                positionMs = s.positionMs,
                durationMs = if (s.durationMs > 0) s.durationMs else _state.value.durationMs,
                // volume 保持 UI 侧的值：VLC 回传的是它自己的音量刻度（0-100 非线性），
                // 换算回来会有舍入抖动，所以这里不用它覆盖。
                message = s.error ?: _state.value.message,
            )
        }
        player.onFinished = {
            // VLC 事件线程 → 切到协程里做切歌
            scope.launch { onTrackFinished() }
        }
        // 播放被 VLC 中断（网络抖动 / 取流地址失效 / 解码错误）→ 自动重连
        player.onInterrupted = { reason ->
            scope.launch { handleInterruption(reason) }
        }
        startPlaybackWatchdog()
    }

    /**
     * 播放看门狗。
     *
     * VLC 偶尔会进入「状态是 playing，但既不出声、进度也不走」的假死：
     * 音频输出被别的程序独占、流卡在缓冲、暂停后 resume 的竞态，都可能触发。
     * 这种情况下 VLC 不会发 error，也没有 stopped，前面那些兜底一个都不会动。
     *
     * 这里每 2 秒采样一次：播放中且位置连续 6 秒没前进 → 认为假死，
     * 重建媒体并回到原位置（最多连续 3 次，超过就提示用户手动切歌）。
     */
    private fun startPlaybackWatchdog() {
        scope.launch {
            var lastPos = -1L
            var stuckTicks = 0
            while (true) {
                kotlinx.coroutines.delay(2000)
                val s = _state.value
                if (!s.playing || s.current == null || s.loading) {
                    stuckTicks = 0
                    lastPos = -1L
                    continue
                }
                if (s.positionMs == lastPos) {
                    stuckTicks++
                    if (stuckTicks >= 3) {
                        stuckTicks = 0
                        lastPos = -1L
                        if (interruptionRetries >= MAX_INTERRUPTION_RETRIES) {
                            AppLog.e(TAG, "播放假死且自动重试已达上限：" + s.current?.title)
                            _state.value = _state.value.copy(
                                message = "播放卡住了，点下一首或重新播放试试",
                            )
                        } else {
                            interruptionRetries++
                            val resumeAt = s.positionMs
                            AppLog.w(
                                TAG,
                                "播放假死（位置 " + resumeAt + "ms 连续 6 秒未前进），第 " +
                                    interruptionRetries + " 次重建媒体",
                            )
                            if (resumeAt > 3000) pendingSeekMs = resumeAt
                            retryCurrent()
                        }
                    }
                } else {
                    stuckTicks = 0
                }
                lastPos = s.positionMs
            }
        }
    }

    // ---------------- 队列 ----------------

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        shuffleOrder = if (_state.value.mode == PlayMode.SHUFFLE) {
            // 用户点的那首排第一，其余随机
            listOf(safeIndex) + tracks.indices.filter { it != safeIndex }.shuffled()
        } else {
            tracks.indices.toList()
        }
        interruptionRetries = 0
        pendingSeekMs = null
        _state.value = _state.value.copy(queue = tracks, index = safeIndex, positionMs = 0L, durationMs = 0L)
        playCurrent()
    }

    /** 把 BV 号 / 链接解析成一首歌并立即播放（播放页的「马上试听」入口） */
    fun playSingle(track: Track) = playQueue(listOf(track), 0)

    fun playAt(index: Int) {
        if (index !in _state.value.queue.indices) return
        interruptionRetries = 0
        pendingSeekMs = null
        _state.value = _state.value.copy(index = index, positionMs = 0L, durationMs = 0L)
        playCurrent()
    }

    /**
     * 本地文件 → VLC 的 mrl（自己拼，不用 Java 的 URI 工具）。
     *
     * 踩过两个坑：
     * 1) File.toURI() 生成 "file:/D:/x"（单斜杠），VLC 当相对路径拼到工作目录后面；
     * 2) 即使改成 Path.toUri()，中文名会被按 JVM 的 sun.jnu.encoding(GBK) 编码成
     *    "%B0%B2%C4%DD..."，而 VLC 按 UTF-8 解码 → 路径变乱码 → 播放失败。
     *    表现就是「纯英文文件名的能播、中文名的全是 ERROR」。
     *
     * 这里手动按 UTF-8 百分号编码，VLC 解码后就是正确的 Unicode 路径。
     */
    private fun localMrl(path: String): String {
        val normalized = java.io.File(path).absolutePath.replace('\\', '/')
        val segments = normalized.split("/")
        val encoded = segments.joinToString("/") { seg ->
            // 盘符段（如 "D:"）原样保留，其余按 UTF-8 百分号编码
            if (seg.length >= 2 && seg[1] == ':') seg else utf8Encode(seg)
        }
        return "file:///" + encoded.trimStart('/')
    }

    /** RFC 3986 的路径段编码（只保留 unreserved 字符 + 常见安全字符） */
    private fun utf8Encode(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val keep = (c in 0x30..0x39) || (c in 0x41..0x5A) || (c in 0x61..0x7A) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (keep) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(String.format(java.util.Locale.ROOT, "%02X", c))
            }
        }
        return sb.toString()
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
            scope.launch {
                withContext(Dispatchers.IO) { player.playLocalFile(path) }
            }
            return
        }

        // 已下载过：直接离线播放，不再取流（FR-9）
        downloadedFile(track.bvid, track.cid)?.let { path ->
            _state.value = _state.value.copy(
                loading = false,
                message = null,
                qualityLabel = "离线",
                durationMs = track.durationMs,
            )
            scope.launch { withContext(Dispatchers.IO) { player.playLocalFile(path) } }
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
        userActionSeq++
        val seq = userActionSeq
        // VLC 里已经没有可操作的媒体（播放中断/停止后）→ resume() 是空操作，
        // 表现为「点播放没反应」。这种情况下直接重新加载当前曲目。
        if (!player.hasActiveMedia()) {
            val track = _state.value.current
            if (track != null) {
                AppLog.i(TAG, "没有活动媒体，重新加载：" + track.title)
                retryCurrent()
            }
            return
        }
        if (_state.value.playing) {
            player.pause()
            AppLog.i(TAG, "用户暂停")
        } else {
            player.resume()
            AppLog.i(TAG, "用户恢复播放")
            // 兜底：VLC 的 setPause(false) 会在「音频输出设备被独占/切换」后静默失败 ——
            // 状态看着是 PAUSED、API 也返回成功，但就是不出声也不走时间。
            // 1.2 秒后如果还没真正播起来，就重建媒体（重新取流）。
            scope.launch {
                kotlinx.coroutines.delay(1200)
                // ★ 这期间用户又按过（最常见就是又点了一下暂停）→ 不是 resume 失败，别重建
                if (seq != userActionSeq) return@launch
                if (!_state.value.playing && _state.value.current != null && !_state.value.loading) {
                    // 先重试一次 resume —— VLC 暂停/恢复之间有竞态，重发一次往往就好了，
                    // 比重建媒体轻得多（不重取流、播放位置也不会丢）
                    AppLog.w(TAG, "resume 未生效，重试一次")
                    player.resume()
                    kotlinx.coroutines.delay(800)
                    if (seq == userActionSeq && !_state.value.playing) {
                        val resumeAt = _state.value.positionMs
                        AppLog.w(TAG, "resume 重试仍无效，自动重建媒体（保留位置 " + resumeAt + "ms）")
                        // ★ 重建后回到原来的播放位置，而不是从头开始
                        if (resumeAt > 3000) pendingSeekMs = resumeAt
                        retryCurrent()
                    }
                }
            }
        }
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
        val target = positionMs.coerceIn(0L, _state.value.durationMs)
        if (_state.value.current == null) return
        // 没有活动媒体时 setTime 无效，UI 上表现为「拖动后又跳回去」。
        // 记下目标位置，重新加载完成后再跳过去。
        if (!player.hasActiveMedia()) {
            AppLog.w(TAG, "没有活动媒体，记录待跳转位置 " + target + "ms 并重新加载")
            pendingSeekMs = target
            retryCurrent()
            return
        }
        player.seekTo(target)
        // ★ 乐观更新：VLC 的 timeChanged 是异步的，等它回来之前 UI 会继续用旧位置，
        //   滑块就「弹回去」。这里立刻把位置写成目标值，滑块停在用户拖到的位置。
        _state.value = _state.value.copy(positionMs = target)
        userActionSeq++
        val seq = userActionSeq
        AppLog.i(TAG, "seek -> " + target + "ms")
        scope.launch {
            kotlinx.coroutines.delay(1800)
            // 这期间用户又拖/又按 → 以最后一次操作为准，跳过本次判定
            if (seq != userActionSeq) return@launch
            // 暂停状态下 timeChanged 不触发，无法判定 seek 是否生效；交给播放时的看门狗
            if (!_state.value.playing) return@launch
            val pos = _state.value.positionMs
            // 播放中正常 seek 后位置应该在「目标 + 1.8s」附近；差得太远才认为真的失败
            if (kotlin.math.abs(pos - target) > 20000) {
                AppLog.w(TAG, "seek 未生效（目标 " + target + "，当前 " + pos + "），重建媒体并跳到目标")
                pendingSeekMs = target
                retryCurrent()
            }
        }
    }

    /**
     * 设置音量。[volume] 是 UI 上的 0-100（也就是期望的系统音量合成器里显示的值）。
     *
     * [persist] 为 false 时只下发不写盘 —— 拖动过程中每 50ms 调一次，
     * 避免每帧写 settings.json。
     */
    /** 把当前音量写进设置（拖动结束后调用；setVolume(persist=false) 已经实时改了 state） */
    fun persistVolume() {
        runCatching { settings?.putString(KEY_VOLUME, _state.value.volume.toString()) }
    }

    fun setVolume(volume: Int, persist: Boolean = true) {
        val ui = volume.coerceIn(0, 100)
        _state.value = _state.value.copy(volume = ui)
        player.setSessionVolume(uiToSessionVolume(ui))
        if (persist) {
            runCatching { settings?.putString(KEY_VOLUME, ui.toString()) }
        }
    }


    /**
     * UI 音量（0-100）→ Windows 会话音量（0..1）。
     *
     * 低端特意做得很平（用户实测反馈：会话音量 1% 在安静环境仍偏响）：
     *   0-10%  ：线性低坡，10% 只到会话音量 1%
     *   10-100%：用 1.1 次方过渡到 100%（比线性略陡一点，过渡自然）
     * 这样 1% 和 2% 之间只差 0.1 个百分点（听感上「只加一点点」），
     * 到了中高音量又不会觉得调不动。
     */
    private fun uiToSessionVolume(ui: Int): Float {
        if (ui <= 0) return 0f
        if (ui >= 100) return 1f
        if (ui <= 10) {
            // 0-10%：很平的低坡，10% 时也只有会话音量 1%
            // 1% -> 0.1%，2% -> 0.2%，5% -> 0.5%
            return 0.001f * ui
        }
        // 10% 以上：从 1% 接到 100%，斜率平缓上升（1.1 次方，接近线性）
        val t = (ui - 10) / 90.0
        return (0.01 + 0.99 * Math.pow(t, 1.1)).toFloat()
    }

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
        _state.value = _state.value.copy(mode = mode, message = "播放模式：" + mode.displayName)
        val queue = _state.value.queue
        val current = _state.value.index
        shuffleOrder = if (mode == PlayMode.SHUFFLE) {
            if (queue.isEmpty() || current !in queue.indices) {
                queue.indices.shuffled()
            } else {
                // 当前这首歌排在第一位，其余随机：当前歌播完后才进入随机顺序，
                // 不会出现「刚切到随机，下一首又跳回列表开头或立刻重复当前歌」
                listOf(current) + queue.indices.filter { it != current }.shuffled()
            }
        } else {
            queue.indices.toList()
        }
    }

    /**
     * VLC 播放被中断（网络抖动 / 取流地址失效 / 解码错误）时的自动恢复。
     *
     * 之前没有这个逻辑：中断后 VLC 停在 ERROR，UI 上点播放没反应、拖进度条又跳回去，
     * 只能靠用户手动换歌。现在自动重取流 + 回到原来的播放位置，最多 3 次。
     */
    private suspend fun handleInterruption(reason: String) {
        val track = _state.value.current ?: return
        if (!track.localPath.isNullOrBlank()) {
            _state.value = _state.value.copy(message = "本地文件播放中断：" + reason)
            return
        }
        if (interruptionRetries >= MAX_INTERRUPTION_RETRIES) {
            AppLog.e(TAG, "播放中断，重试已达上限：" + track.title)
            _state.value = _state.value.copy(
                message = "播放中断（" + reason + "），点播放按钮重试",
            )
            return
        }
        interruptionRetries++
        val resumeAt = _state.value.positionMs
        AppLog.w(TAG, "播放中断（" + reason + "），第 " + interruptionRetries + " 次自动重连：" + track.title)
        _state.value = _state.value.copy(
            message = "播放中断，正在重新连接（第 " + interruptionRetries + " 次）…",
        )
        kotlinx.coroutines.delay(600)
        streamUrls.invalidate(track.bvid, track.cid)
        playCurrent()
        if (resumeAt > 3000) {
            kotlinx.coroutines.delay(900)
            player.seekTo(resumeAt)
            AppLog.i(TAG, "已恢复到 " + resumeAt + "ms")
        }
    }

    /** 取流地址过期 / 播放失败：清缓存重取一次 */
    fun retryCurrent() {
        val key = currentKey ?: return
        interruptionRetries = 0
        streamUrls.invalidate(key.first, key.second)
        playCurrent()
        // 之前拖动进度条但当时没有活动媒体 → 重新加载后跳到目标位置
        pendingSeekMs?.let { pos ->
            pendingSeekMs = null
            scope.launch {
                kotlinx.coroutines.delay(900)
                player.seekTo(pos)
            }
        }
    }

    /** 定时关闭：等当前这首自然播完再停 */
    fun requestStopAfterCurrent() {
        stopAfterCurrentTrack = true
        _state.value = _state.value.copy(message = "定时到点：本曲播完将停止播放")
    }

    /** 立即停止 */
    fun stopNow() {
        stopAfterCurrentTrack = false
        player.stop()
        _state.value = _state.value.copy(playing = false, message = "已停止播放")
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
        // 定时关闭的「等本曲播完再停」优先于自动切歌
        if (stopAfterCurrentTrack) {
            stopAfterCurrentTrack = false
            player.stop()
            _state.value = _state.value.copy(playing = false, message = "定时到点，本曲播完已停止播放")
            return
        }
        when (_state.value.mode) {
            PlayMode.REPEAT_ONE -> playCurrent()
            else -> advance(manual = false)
        }
    }

    /**
     * 随机模式下一轮播完时重新洗牌。
     *
     * 把刚播完的那首排到最后，其余随机 —— 这样新一轮不会立刻重复刚听过的歌。
     */
    private fun reshuffleForNextRound() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val current = _state.value.index
        val others = queue.indices.filter { it != current }.shuffled()
        shuffleOrder = if (current in queue.indices) others + current else others
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

            _state.value.mode == PlayMode.SHUFFLE -> {
                // 随机播放是「无限随机」：一轮播完重新洗牌接着放，不自动停止
                AppLog.i(TAG, "随机播放：一轮播完，重新洗牌继续")
                reshuffleForNextRound()
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
