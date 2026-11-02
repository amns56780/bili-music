package com.bilimusic.app.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-4 / FR-5 / FR-9 / FR-10 的播放偏好。
 * 只做偏好读写，不含业务逻辑。
 */
@Singleton
class PlaybackPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** 播放模式（FR-4），对应 PlayMode 枚举名 */
    val playMode: Flow<String> = dataStore.data.map { it[KEY_PLAY_MODE] ?: DEFAULT_PLAY_MODE }

    /** 音质档位（FR-10），对应 AudioQualityOption 枚举名 */
    val audioQualityOption: Flow<String> =
        dataStore.data.map { it[KEY_QUALITY] ?: DEFAULT_QUALITY }

    /** 定时关闭默认档位（分钟） */
    val sleepTimerDefaultMinutes: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_MINUTES] ?: DEFAULT_SLEEP_MINUTES }

    /** 定时关闭：默认开启「播完当前歌曲后停止」（FR-5 要求默认开启） */
    val sleepTimerStopAfterCurrent: Flow<Boolean> =
        dataStore.data.map { it[KEY_SLEEP_STOP_AFTER] ?: true }

    /**
     * FR-5：定时到期时刻（用 elapsedRealtime，防止用户改系统时间）。
     * 0 表示没有在计时。App 被杀后靠它恢复计时。
     */
    val sleepTimerDeadlineElapsed: Flow<Long> =
        dataStore.data.map { it[KEY_SLEEP_DEADLINE] ?: 0L }

    /** FR-5：已经到点、正在等「本曲播完」的状态，也要能跨进程恢复 */
    val sleepTimerWaitingForTrackEnd: Flow<Boolean> =
        dataStore.data.map { it[KEY_SLEEP_WAITING] ?: false }

    /** 缓存上限（字节），默认 2GB（FR-9） */
    val cacheLimitBytes: Flow<Long> =
        dataStore.data.map { it[KEY_CACHE_LIMIT] ?: DEFAULT_CACHE_LIMIT_BYTES }

    /** 上次播放的曲目 id，用于 FR 恢复播放位置 */
    val lastPlayedSongId: Flow<Long?> =
        dataStore.data.map { it[KEY_LAST_SONG_ID]?.takeIf { id -> id > 0L } }

    /** 暂停后是否保留通知（FR-8 可选项，默认保留） */
    val keepNotificationWhenPaused: Flow<Boolean> =
        dataStore.data.map { it[KEY_KEEP_NOTIFICATION] ?: true }

    /**
     * 前台软件播放音频时后台音乐是否继续播放（默认 true）。
     *
     * true  = 不申请音频焦点（handleAudioFocus = false）—— 与其他 App 同时出声、互不打断，这是本 App 的默认行为；
     * false = 申请音频焦点（handleAudioFocus = true）—— 标准音乐 App 行为：别的 App 开始放声音时本 App 自动暂停。
     */
    val keepPlayingWithOtherApps: Flow<Boolean> =
        dataStore.data.map { it[KEY_KEEP_PLAYING_WITH_OTHERS] ?: DEFAULT_KEEP_PLAYING_WITH_OTHERS }

    suspend fun setPlayMode(mode: String) = edit { it[KEY_PLAY_MODE] = mode }

    suspend fun setAudioQuality(option: String) = edit { it[KEY_QUALITY] = option }

    suspend fun setSleepTimerDefaultMinutes(minutes: Int) = edit { it[KEY_SLEEP_MINUTES] = minutes }

    suspend fun setSleepTimerStopAfterCurrent(enabled: Boolean) =
        edit { it[KEY_SLEEP_STOP_AFTER] = enabled }

    suspend fun setSleepTimerDeadlineElapsed(value: Long) = edit { it[KEY_SLEEP_DEADLINE] = value }

    suspend fun setSleepTimerWaitingForTrackEnd(value: Boolean) =
        edit { it[KEY_SLEEP_WAITING] = value }

    /**
     * 保存定时时同时记下「保存那一刻的开机时长」与「开机时刻标记」。
     *
     * 为什么需要：定时截止点用的是 `SystemClock.elapsedRealtime()`，而它是**开机以来的毫秒数，重启会归零**。
     * 只存截止点的话，手机重启后那个数会变成"未来的某个时刻"，App 一启动就把幽灵定时恢复出来，
     * 几小时后"到点"，表现就是「随机播放着，某首歌放完突然暂停」（真实踩过的坑）。
     */
    suspend fun setSleepTimerBootInfo(savedAtElapsed: Long, bootMarker: Long) = edit {
        it[KEY_SLEEP_SAVED_AT_ELAPSED] = savedAtElapsed
        it[KEY_SLEEP_BOOT_MARKER] = bootMarker
    }

    val sleepTimerSavedAtElapsed: Flow<Long> =
        dataStore.data.map { it[KEY_SLEEP_SAVED_AT_ELAPSED] ?: 0L }

    /** 开机时刻标记 = 墙上时钟 - 开机时长；设备重启后这个值会变 */
    val sleepTimerBootMarker: Flow<Long> =
        dataStore.data.map { it[KEY_SLEEP_BOOT_MARKER] ?: 0L }

    suspend fun setCacheLimitBytes(bytes: Long) = edit { it[KEY_CACHE_LIMIT] = bytes }

    suspend fun setLastPlayedSongId(songId: Long) = edit { it[KEY_LAST_SONG_ID] = songId }

    suspend fun setKeepNotificationWhenPaused(keep: Boolean) =
        edit { it[KEY_KEEP_NOTIFICATION] = keep }

    suspend fun setKeepPlayingWithOtherApps(keep: Boolean) =
        edit { it[KEY_KEEP_PLAYING_WITH_OTHERS] = keep }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    suspend fun snapshot(): Snapshot = Snapshot(
        playMode = playMode.first(),
        audioQualityOption = audioQualityOption.first(),
        sleepTimerDefaultMinutes = sleepTimerDefaultMinutes.first(),
        sleepTimerStopAfterCurrent = sleepTimerStopAfterCurrent.first(),
        cacheLimitBytes = cacheLimitBytes.first(),
        lastPlayedSongId = lastPlayedSongId.first(),
    )

    data class Snapshot(
        val playMode: String,
        val audioQualityOption: String,
        val sleepTimerDefaultMinutes: Int,
        val sleepTimerStopAfterCurrent: Boolean,
        val cacheLimitBytes: Long,
        val lastPlayedSongId: Long?,
    )

    companion object {
        /** 默认播放模式：列表循环（整单放完自动接着放，不会莫名停） */
        const val DEFAULT_PLAY_MODE = "REPEAT_ALL"
        const val DEFAULT_QUALITY = "AUTO"
        const val DEFAULT_SLEEP_MINUTES = 30
        const val DEFAULT_CACHE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024

        /** 默认与其他 App 同时播放（不申请音频焦点），保持原有行为 */
        const val DEFAULT_KEEP_PLAYING_WITH_OTHERS = true

        private val KEY_PLAY_MODE = stringPreferencesKey("play_mode")
        private val KEY_QUALITY = stringPreferencesKey("audio_quality")
        private val KEY_SLEEP_MINUTES = intPreferencesKey("sleep_timer_default_minutes")
        private val KEY_SLEEP_STOP_AFTER = booleanPreferencesKey("sleep_timer_stop_after_current")
        private val KEY_SLEEP_DEADLINE = longPreferencesKey("sleep_timer_deadline_elapsed")
        private val KEY_SLEEP_WAITING = booleanPreferencesKey("sleep_timer_waiting_for_track_end")
        private val KEY_SLEEP_SAVED_AT_ELAPSED = longPreferencesKey("sleep_timer_saved_at_elapsed")
        private val KEY_SLEEP_BOOT_MARKER = longPreferencesKey("sleep_timer_boot_marker")
        private val KEY_CACHE_LIMIT = longPreferencesKey("cache_limit_bytes")
        private val KEY_LAST_SONG_ID = longPreferencesKey("last_played_song_id")
        private val KEY_KEEP_NOTIFICATION = booleanPreferencesKey("keep_notification_when_paused")
        private val KEY_KEEP_PLAYING_WITH_OTHERS = booleanPreferencesKey("keep_playing_with_other_apps")
    }
}
