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
        ":http-referrer=${BiliHeaderInterceptor.REFERER}",
        ":http-user-agent=${BiliHeaderInterceptor.USER_AGENT}",
        ":network-caching=1000",
    )
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    private var state = State()
    private var finishedCounter = 0L

    /** 状态变化回调（在 VLC 的事件线程上调用，UI 侧注意切回主线程） */
    var onStateChanged: (State) -> Unit = {}

    /** 一首播完的回调 */
    var onFinished: () -> Unit = {}

    init {
        player.audio().setVolume(state.volume)
        player.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) = publish(playing = true)
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
        player.media().play(url)
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

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        player.audio().setVolume(clamped)
        state = state.copy(volume = clamped)
        onStateChanged(state)
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
