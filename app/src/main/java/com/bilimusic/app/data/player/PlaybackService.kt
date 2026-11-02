package com.bilimusic.app.data.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bilimusic.app.MainActivity
import com.bilimusic.app.data.local.prefs.PlaybackPrefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import kotlin.random.Random

/**
 * FR-6 / FR-8 播放服务：MediaSessionService + 唯一的 ExoPlayer 实例。
 *
 * ★ 音频焦点：默认 `setAudioAttributes(audioAttributes, handleAudioFocus = false)`
 *   —— 不接管音频焦点，所以抖音/微信在放声音时本 App 的音量不降、不暂停。
 *   设置页的「与其他 App 同时播放」开关可以关掉它：关掉后改为
 *   `handleAudioFocus = true`，别的 App 开始放声音时由 AudioFocusManager 自动暂停本 App。
 *   **绝对不要**自己调用 AudioManager.requestAudioFocus()：焦点必须交给 ExoPlayer 统一管理，
 *   否则会和它内部的 AudioFocusManager 打架（重复申请/无法正确释放）。
 * ★ `setHandleAudioBecomingNoisy(false)`：拔耳机也不暂停（刻意设置）。
 * ★ `setWakeMode(C.WAKE_MODE_NETWORK)`：息屏后仍能持续缓冲，防止后台断流。
 *
 * MediaSession 与音频焦点是两套独立机制，不申请焦点不影响通知栏/锁屏/线控。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerDataSources: PlayerDataSources

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var sleepTimer: SleepTimer

    @Inject
    lateinit var playbackPrefs: PlaybackPrefs

    private var mediaSession: MediaSession? = null

    private lateinit var player: ExoPlayer
    private lateinit var audioAttributes: AudioAttributes

    /** 当前是否把音频焦点交给 ExoPlayer 管理（true = 申请焦点、别的 App 出声时自动暂停） */
    private var handleAudioFocus = false

    /** 跟着服务生命周期走的协程作用域，用于监听设置里的音频焦点开关 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val dataSourceFactory = playerDataSources.create(okHttpClient)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(BiliLoadErrorHandlingPolicy())

        // 初始按「不接管焦点」构建：设置里默认就是「与其他 App 同时播放」。
        // 如果用户改过开关，下面 collect 的第一帧会立刻把模式切过去（setAudioAttributes 会重新评估焦点）。
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // 固定 seed 的洗牌顺序：同一次会话内随机顺序稳定，切歌不会重排（任务书 FR-4）。
        // MediaController 不支持 setShuffleOrder，所以只能在服务端设。
        //
        // 注意 DefaultShuffleOrder 第一个参数是**长度**不是种子。这里先给 0，并在播放列表
        // 长度变化时用**同一个 seed** 重建一份长度正确的顺序：长度和列表对不上时
        // getNextMediaItemIndex() 有可能返回 INDEX_UNSET，表现就是「一首播完就停」。
        val shuffleSeed = Random.nextInt().toLong()
        var shuffleOrderLength = 0
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(0, shuffleSeed))
        player.addListener(
            object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    val count = player.mediaItemCount
                    if (count > 0 && shuffleOrderLength != count) {
                        shuffleOrderLength = count
                        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(count, shuffleSeed))
                    }
                }
            },
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppPendingIntent())
            .build()

        // 通知栏带上「定时关闭剩余时间」辅助行（FR-8）
        setMediaNotificationProvider(BiliMediaNotificationProvider(this, sleepTimer))

        // FR-5：定时关闭挂在播放器回调上，到点后由它决定何时真正停止
        sleepTimer.attach(
            player = player,
            // 任务书要求：停止 = pause() + 停止前台服务。
            // 注意**不要 stopSelf()**：服务被杀掉会让 UI 的 MediaController 断连，
            // 播放页就会停在「正在播放」的旧状态上（进度条冻住）。暂停 + 让 Media3
            // 收掉前台通知即可，服务留着，UI 能正确显示「已暂停」。
            onStopPlayback = {
                player.pause()
                refreshPlaybackNotification()
            },
            onRefreshNotification = { refreshPlaybackNotification() },
        )

        // 设置页「与其他 App 同时播放」开关：开着（默认）= 不接管音频焦点；
        // 关掉 = 把焦点交给 ExoPlayer 管理，别的 App 开始放声音时自动暂停本 App。
        // 放在这里而不是构建参数里，是为了切换开关时立刻生效、不用重启服务。
        serviceScope.launch {
            playbackPrefs.keepPlayingWithOtherApps.collect { keep ->
                applyAudioFocusMode(keep)
            }
        }
    }

    /**
     * 切换音频焦点模式。
     *
     * ExoPlayer 的 setAudioAttributes 内部会立刻重新评估焦点：
     * 开启时马上申请（正在播放就抢焦点），关闭时立刻 abandon，所以不用暂停/重播。
     */
    private fun applyAudioFocusMode(keepPlayingWithOtherApps: Boolean) {
        val handleFocus = !keepPlayingWithOtherApps
        if (handleFocus == handleAudioFocus) return
        handleAudioFocus = handleFocus
        Log.i(TAG, "音频焦点模式切换：handleAudioFocus=" + handleFocus)
        runCatching { player.setAudioAttributes(audioAttributes, handleFocus) }
            .onFailure { Log.w(TAG, "切换音频焦点失败", it) }
    }

    /** 定时状态变化 / 每秒倒计时时刷新通知栏 */
    fun refreshPlaybackNotification() {
        val session = mediaSession ?: return
        runCatching { onUpdateNotification(session, false) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户在最近任务里划掉 App：
     * 正在播放就继续播（后台播放不能被误杀）；没在播就停掉服务，避免通知栏一直挂着。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val playing = player != null && player.playWhenReady && player.mediaItemCount > 0
        if (!playing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        sleepTimer.detach()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** 点通知回到 App */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
