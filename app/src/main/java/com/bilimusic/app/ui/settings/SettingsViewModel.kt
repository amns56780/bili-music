package com.bilimusic.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.local.entity.DownloadRecordEntity
import com.bilimusic.app.data.player.PlaybackCacheManager
import com.bilimusic.app.data.repository.AuthRepository
import com.bilimusic.app.data.repository.DownloadRepository
import com.bilimusic.app.data.repository.LoginState
import com.bilimusic.app.data.local.prefs.PlaybackPrefs
import com.bilimusic.app.domain.model.AudioQualityOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val audioQuality: AudioQualityOption = AudioQualityOption.AUTO,
    val keepPlayingWithOtherApps: Boolean = PlaybackPrefs.DEFAULT_KEEP_PLAYING_WITH_OTHERS,
    val cacheLimitBytes: Long = PlaybackPrefs.DEFAULT_CACHE_LIMIT_BYTES,
    val cacheUsedBytes: Long = 0L,
    val qualityDialogOpen: Boolean = false,
    val cacheDialogOpen: Boolean = false,
    val limitDialogOpen: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackPrefs: PlaybackPrefs,
    private val cacheManager: PlaybackCacheManager,
) : ViewModel() {

    val loginState: StateFlow<LoginState> = authRepository.state

    val downloads: StateFlow<List<DownloadRecordEntity>> = downloadRepository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        downloadRepository.syncIfNeeded()
        viewModelScope.launch {
            playbackPrefs.audioQualityOption.collect { name ->
                _uiState.value = _uiState.value.copy(
                    audioQuality = AudioQualityOption.fromNameOrAuto(name),
                )
            }
        }
        viewModelScope.launch {
            playbackPrefs.keepPlayingWithOtherApps.collect { keep ->
                _uiState.value = _uiState.value.copy(keepPlayingWithOtherApps = keep)
            }
        }
        viewModelScope.launch {
            playbackPrefs.cacheLimitBytes.collect { limit ->
                _uiState.value = _uiState.value.copy(cacheLimitBytes = limit)
            }
        }
        refreshCacheUsage()
    }

    fun refreshCacheUsage() {
        viewModelScope.launch {
            val used = cacheManager.usedBytes()
            _uiState.value = _uiState.value.copy(cacheUsedBytes = used)
        }
    }

    // ---------------- FR-10 音质 ----------------

    fun openQualityDialog() {
        _uiState.value = _uiState.value.copy(qualityDialogOpen = true)
    }

    fun closeQualityDialog() {
        _uiState.value = _uiState.value.copy(qualityDialogOpen = false)
    }

    /**
     * 切换音质：只改偏好，**不重启播放器**——按任务书要求「当前歌曲不中断，从下一首开始生效」。
     * 播放链路在每次取流时读取这个偏好，所以下一首自然就是新音质。
     */
    fun setAudioQuality(option: AudioQualityOption) {
        viewModelScope.launch {
            playbackPrefs.setAudioQuality(option.name)
            _uiState.value = _uiState.value.copy(qualityDialogOpen = false)
            _messages.send("音质已切换为「${option.settingLabel}」，当前歌曲不中断，从下一首开始生效")
        }
    }

    // ---------------- 音频焦点开关 ----------------

    /**
     * 切换「与其他 App 同时播放」：只写偏好，播放服务监听这个偏好，
     * 会立刻调用 ExoPlayer.setAudioAttributes 重新评估音频焦点（不用重启服务/播放器）。
     */
    fun setKeepPlayingWithOtherApps(keep: Boolean) {
        viewModelScope.launch {
            playbackPrefs.setKeepPlayingWithOtherApps(keep)
            _messages.send(
                if (keep) {
                    "已开启：抖音/微信等前台软件出声时，本 App 继续播放"
                } else {
                    "已关闭：其他 App 开始放声音时，本 App 会自动暂停"
                },
            )
        }
    }

    // ---------------- FR-9 缓存 ----------------

    fun openCacheDialog() {
        refreshCacheUsage()
        _uiState.value = _uiState.value.copy(cacheDialogOpen = true)
    }

    fun closeCacheDialog() {
        _uiState.value = _uiState.value.copy(cacheDialogOpen = false)
    }

    fun openLimitDialog() {
        _uiState.value = _uiState.value.copy(limitDialogOpen = true)
    }

    fun closeLimitDialog() {
        _uiState.value = _uiState.value.copy(limitDialogOpen = false)
    }

    fun setCacheLimit(bytes: Long) {
        viewModelScope.launch {
            playbackPrefs.setCacheLimitBytes(bytes)
            _uiState.value = _uiState.value.copy(limitDialogOpen = false)
            _messages.send("缓存上限已设为 ${formatBytes(bytes)}（重启 App 后生效）")
        }
    }

    fun deleteDownload(cacheKey: String, title: String) {
        viewModelScope.launch {
            downloadRepository.removeDownload(cacheKey)
            refreshCacheUsage()
            _messages.send("已删除缓存：$title")
        }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            downloadRepository.clearAll()
            refreshCacheUsage()
            _messages.send("已清空全部缓存")
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _messages.send("已退出登录")
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
