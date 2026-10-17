package com.bilimusic.desktop.player

import com.bilimusic.app.data.remote.BiliHeaderInterceptor
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
        // Playback starts sooner with a smaller cache; B站音频码率不高，300ms 足够
        "--network-caching=300",
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

    /** 状态变化回调（在 VLC 的事件线程上调用，UI 侧注意切回主线程） */
    var onStateChanged: (State) -> Unit = {}

    /** 一首播完的回调 */
    var onFinished: () -> Unit = {}

    init {
        player.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
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
                override fun paused(mediaPlayer: MediaPlayer) = publish(playing = false)
                override fun stopped(mediaPlayer: MediaPlayer) = publish(playing = false)
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) =
                    publish(positionMs = newTime)

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) =
                    publish(durationMs = newLength)

                override fun finished(mediaPlayer: MediaPlayer) {
                    finishedCounter += 1
                    publish(playing = false, positionMs = 0L, finishedTick = finishedCounter)
                    onFinished()
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    // 打印 VLC 实际在放的地址和状态，定位「地址过期」这类问题时必需
                    runCatching {
                        val mrl = mediaPlayer.media().info().mrl()
                        val st = mediaPlayer.status().state()
                        println("[Vlc] ERROR state=" + st + " mrl=" + mrl)
                    }
                    state = state.copy(error = "这个地址播不了（可能已过期），正在重试")
                    onStateChanged(state)
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
        player.controls().stop()
        publish(playing = false, positionMs = 0L)
    }

    /** 切歌前把状态归零，避免 UI 短暂显示上一首的时长 */
    fun resetForNewTrack() {
        state = state.copy(positionMs = 0L, durationMs = 0L)
        onStateChanged(state)
    }

    fun seekTo(positionMs: Long) {
        player.controls().setTime(positionMs)
        publish(positionMs = positionMs)
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
