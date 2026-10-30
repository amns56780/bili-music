package com.bilimusic.desktop.player

import com.bilimusic.app.data.remote.BiliHeaderInterceptor
import com.bilimusic.app.util.AppLog
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/**
 * VLC 播放内核封装（Windows 端）。
 *
 * 为什么不自己解码：B 站音频是 m4a/aac/flac，VLC 全都能放，且支持 http 直接拉流；
 * 用 VLC 后不需要额外引入解码器。
 *
 * ★ 关键：bilivideo 域名必须带 Referer/User-Agent，否则 403 —— 这里把安卓端
 *   [BiliHeaderInterceptor] 里同一套头通过 libvlc 的 `:http-referrer` / `:http-user-agent`
 *   传下去，保证两端取流行为一致。
 *
 * 音频播放不需要视频窗口（不调 setVideoSurface），所以是无界面播放。
 */
class VlcPlayer {

    /** 播放器状态快照 */
    data class State(
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val volume: Int = 80,
        /** 一首自然播完（用于队列自动切下一首） */
        val finishedTick: Long = 0L,
        val error: String? = null,
    )

    // 与安卓端 OkHttp 的伪造头保持一致，否则 bilivideo 会 403。
    // 这些是 libvlc 的全局选项，对每个媒体都生效——本项目所有地址都是 B 站的，正合适。
    private val factory: MediaPlayerFactory = MediaPlayerFactory(
        "--no-video",
        "--quiet",
        // ★ 这里必须是 "--" 前缀（libvlc 的全局实例参数）。
        // 之前写成 ":http-referrer=..."（单冒号是 media 选项语法），传给 MediaPlayerFactory
        // 会被静默忽略 —— 请求不带 Referer/UA，B 站 CDN 直接 403，表现为「这个地址播不了」。
        "--http-referrer=" + BiliHeaderInterceptor.REFERER,
        "--http-user-agent=" + BiliHeaderInterceptor.USER_AGENT,
        // 网络缓冲。实测踩坑：
        //   300ms  → 偶发假死（缓冲太小，抖动就断流）
        //   3000ms → 每首歌 11 秒左右必假死（VLC 3.0 的 http 流在长缓冲下
        //            读满一段后不再续读，播放到缓冲末尾就停住）
        // 1000ms 是实测的稳定值。
        "--network-caching=1000",
        // 本地文件也留一点缓冲，避免磁盘/解码抖动
        "--file-caching=1000",
        // Explicit low-latency Windows audio output module (falls back automatically)
        "--aout=mmdevice",
    )
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    private var state = State()

    /**
     * 目标音量（0..1），直接写 Windows 音频会话音量。
     *
     * 不再用 VLC 自己的音量：VLC 每次开始播放都会把会话音量重置成它自己的 gain，
     * 所以这里记住目标值，在 playing 回调里重新应用一次。
     */
    @Volatile
    private var sessionVolume: Float = 0.8f
    private var finishedCounter = 0L

    /** 用户主动停止（避免把主动停止误判成「意外中断」） */
    @Volatile
    private var userStopped = false

    /** 正在切歌（切歌时的 stopped 也是正常现象） */
    @Volatile
    private var switchingTrack = false

    /** 状态变化回调（在 VLC 的事件线程上调用，UI 侧注意切回主线程） */
    var onStateChanged: (State) -> Unit = {}

    /** 一首播完的回调 */
    var onFinished: () -> Unit = {}

    /** 播放被中断（VLC 报错 / 意外停止）→ 上层做自动重连 */
    var onInterrupted: ((reason: String) -> Unit)? = null

    /**
     * 当前 VLC 里是否还有一个「可以操作」的媒体。
     *
     * 播放中断后 VLC 会停在 ERROR/STOPPED，此时 resume()/setTime() 都是空操作 ——
     * UI 上就表现为「点播放没反应、拖进度条又跳回去」。上层据此决定是重载还是 resume。
     */
    fun hasActiveMedia(): Boolean = runCatching {
        val st = player.status().state()
        st != uk.co.caprica.vlcj.player.base.State.STOPPED &&
            st != uk.co.caprica.vlcj.player.base.State.ERROR &&
            st != uk.co.caprica.vlcj.player.base.State.ENDED
    }.getOrDefault(false)

    init {
        player.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    userStopped = false
                    switchingTrack = false
                    AppLog.i("VlcPlayer", "事件: playing")
                    // VLC 每开始播放新媒体都会把会话音量重置成它自己的 gain（1.0），
                    // 所以这里把目标音量重新写回 Windows 会话一次。
                    applySessionVolume()
                    // 音频会话要等 VLC 真正开始输出才注册（启动时枚举不到自己），
                    // 延迟再补一次，确保接管成功。
                    Thread {
                        runCatching {
                            Thread.sleep(700)
                            applySessionVolume()
                        }
                    }.apply { isDaemon = true }.start()
                    publish(playing = true)
                }
                override fun paused(mediaPlayer: MediaPlayer) {
                    AppLog.i("VlcPlayer", "事件: paused")
                    publish(playing = false)
                }

                override fun stopped(mediaPlayer: MediaPlayer) {
                    AppLog.w(
                        "VlcPlayer",
                        "事件: stopped (userStopped=" + userStopped + ", switching=" + switchingTrack + ")",
                    )
                    publish(playing = false)
                    // 既不是用户点停止、也不是切歌 → 网络/解码层把播放弄停了，交给上层重连
                    if (!userStopped && !switchingTrack) {
                        println("[Vlc] 播放意外停止（非用户操作）")
                        onInterrupted?.invoke("播放意外停止")
                    }
                }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) =
                    publish(positionMs = newTime)

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) =
                    publish(durationMs = newLength)

                override fun finished(mediaPlayer: MediaPlayer) {
                    userStopped = true // 自然播完，不算中断
                    finishedCounter += 1
                    publish(playing = false, positionMs = 0L, finishedTick = finishedCounter)
                    onFinished()
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    // 打印 VLC 实际在放的地址和状态，定位「地址过期」这类问题时必需
                    runCatching {
                        val mrl = mediaPlayer.media().info().mrl()
                        val st = mediaPlayer.status().state()
                        AppLog.e("VlcPlayer", "事件: ERROR state=" + st + " mrl=" + mrl)
                    }
                    state = state.copy(error = "播放中断，正在重新连接…")
                    onStateChanged(state)
                    onInterrupted?.invoke("VLC 报错")
                }
            },
        )
    }

    private fun publish(
        playing: Boolean = state.playing,
        positionMs: Long = state.positionMs,
        durationMs: Long = state.durationMs,
        finishedTick: Long = state.finishedTick,
    ) {
        state = state.copy(
            playing = playing,
            positionMs = positionMs,
            durationMs = durationMs,
            finishedTick = finishedTick,
        )
        onStateChanged(state)
    }

    /**
     * 播放一个音频地址（已带过期时间，过期后由上层重新取流再调用）。
     */
    /**
     * 播放本地文件。
     *
     * 关键：不能用 media().play(String) —— 那个走 libvlc_media_new_location，
     * 按 URL 解析，中文路径会被折腾成乱码（实测 "Candy_Wind - 青空.flac" 直接 ERROR）。
     * PathResource 走 libvlc_media_new_path，VLC 直接用宽字符路径打开文件，
     * 中文/空格/括号都不需要转义。
     */
    fun playLocalFile(path: String) {
        state = state.copy(error = null, positionMs = 0L)
        runCatching {
            // VLC 的 mrl 解析器对「没有协议前缀的绝对路径」按本地文件打开，
            // 和命令行 vlc "D:\music\x.flac" 一样。这比 file:/// URI 可靠得多：
            // 不需要我们把中文路径做百分号编码（之前按 UTF-8 编码后 VLC 仍读错）。
            player.media().play(path, ":audio-volume=1.00")
        }.onFailure {
            println("[Vlc] 本地文件播放失败: " + it)
            state = state.copy(error = "本地文件打不开：" + it.message)
            onStateChanged(state)
        }
    }

    fun play(url: String) {
        state = state.copy(error = null, positionMs = 0L)
        // Volume is passed as a MEDIA option so it is applied the moment playback starts.
        // VLC resets the audio volume for every new media, so setting it only afterwards
        // (or only once at startup) always loses the race - this is the reliable fix.
        // --audio-volume takes a float where 1.0 == 100%.
        // VLC 自身音量固定满幅（增益 1.0），音量完全交给 Windows 会话控制，
        // 避免 VLC 的立方曲线和会话音量叠成两层。
        player.media().play(
            normalizeLocalMrl(url),
            ":audio-volume=1.00",
            ":http-referrer=" + BiliHeaderInterceptor.REFERER,
            ":http-user-agent=" + BiliHeaderInterceptor.USER_AGENT,
        )
    }

    fun pause() {
        player.controls().setPause(true)
        publish(playing = false)
    }

    fun resume() {
        player.controls().setPause(false)
    }

    fun stop() {
        userStopped = true
        player.controls().stop()
        publish(playing = false, positionMs = 0L)
    }

    /** 切歌前把状态归零，避免 UI 短暂显示上一首的时长 */
    fun resetForNewTrack() {
        switchingTrack = true
        state = state.copy(positionMs = 0L, durationMs = 0L)
        onStateChanged(state)
    }

    fun seekTo(positionMs: Long) {
        player.controls().setTime(positionMs)
        publish(positionMs = positionMs)
    }

    // ---------------- 输出设备 ----------------

    /** 当前 VLC 枚举到的音频输出设备（VLC 自己的 mmdevice 插件枚举，最准） */
    fun outputDevices(): List<uk.co.caprica.vlcj.player.base.AudioDevice> = runCatching {
        player.audio().outputDevices().toList()
    }.getOrElse { emptyList() }

    /** 当前输出模块名（正常是 mmdevice） */
    fun audioOutputModule(): String? = runCatching { player.audio().outputDevice() }.getOrNull()

    /**
     * 切换到指定音频输出设备（运行时生效，不需要重载媒体）。
     * VLC 3 在 Windows 上走 mmdevice 插件，deviceId 用 VLC 枚举出来的值。
     */
    fun setOutputDevice(deviceId: String) {
        runCatching {
            player.audio().setOutput("mmdevice")
            player.audio().setOutputDevice("mmdevice", deviceId)
        }
    }

    /** 恢复系统默认设备（清空 output device 设置） */
    fun resetOutputDevice() {
        runCatching {
            player.audio().setOutput("mmdevice")
            player.audio().setOutputDevice("mmdevice", "default")
        }
    }

    /**
     * 设置音量（0..1），直接写本进程的 Windows 音频会话音量。
     *
     * 走 WASAPI 的好处：即时生效（不用等 VLC 的异步命令队列）、精度 0.1%、
     * 音量合成器里显示的就是这个值。
     *
     * 拿不到会话时（极少见）回退到 VLC 自己的音量：VLC 是立方曲线，
     * 所以要用 cbrt 反推，保证会话里显示的值仍然对得上。
     */
    fun setSessionVolume(volume: Float) {
        sessionVolume = volume.coerceIn(0f, 1f)
        applySessionVolume()
        state = state.copy(volume = Math.round(sessionVolume * 100).toInt())
        onStateChanged(state)
    }

    /**
     * Java 的 File.toURI() 生成 "file:/D:/x"，VLC 会当相对路径处理。
     * 这里统一规范成 "file:///D:/x"（对 http 地址原样返回）。
     */
    private fun normalizeLocalMrl(url: String): String = when {
        url.startsWith("file:///") -> url
        url.startsWith("file:/") -> "file:///" + url.removePrefix("file:/")
        else -> url
    }

    private fun applySessionVolume() {
        if (WindowsAudioSession.setVolume(sessionVolume)) return
        // 会话失效（例如音频输出重建）→ 重新枚举一次再试
        WindowsAudioSession.invalidate()
        if (WindowsAudioSession.setVolume(sessionVolume)) return
        // 最终回退：用 VLC 自己的音量（立方曲线 → 反推）
        val vlcVolume = Math.round(100.0 * Math.cbrt(sessionVolume.toDouble())).toInt().coerceIn(0, 100)
        runCatching { player.audio().setVolume(vlcVolume) }
    }

    fun snapshot(): State = state

    fun consumeError() {
        state = state.copy(error = null)
        onStateChanged(state)
    }

    fun release() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}
