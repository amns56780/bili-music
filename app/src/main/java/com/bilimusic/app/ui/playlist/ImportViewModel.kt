package com.bilimusic.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.repository.ImportRepository
import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.FavFolder
import com.bilimusic.app.domain.model.ImportProgress
import com.bilimusic.app.domain.model.ImportReport
import com.bilimusic.app.domain.model.PlaylistNameConflict
import com.bilimusic.app.util.BiliLinkParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportUiState(
    val selectedTab: Int = 0,
    // Tab 1 收藏夹
    val favFolders: List<FavFolder> = emptyList(),
    val favLoading: Boolean = false,
    val favError: String? = null,
    val selectedFolderIds: Set<Long> = emptySet(),
    // Tab 3 UP主投稿
    val uploadMid: String = "",
    val uploadBlocked: Boolean = false,
    val uploadBlockReason: String? = null,
    // Tab 4 合集
    val videoInput: String = "",
    // 导入进行中
    val running: Boolean = false,
    val progressText: String = "",
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val lastReport: ImportReport? = null,
    val lastTitle: String = "",
) {
    val canImportFavorites: Boolean get() = !running && selectedFolderIds.isNotEmpty()
    val canImportUploads: Boolean get() = !running && !uploadBlocked && uploadMid.isNotBlank()
    val canImportVideo: Boolean get() = !running && videoInput.isNotBlank()
    val progressFraction: Float?
        get() = if (progressTotal > 0) (progressCurrent.toFloat() / progressTotal).coerceIn(0f, 1f) else null
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importRepository: ImportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var importJob: Job? = null

    /** 导入碰上歌单重名时，挂起等待用户在对话框里选择 */
    private val duplicateNameResolver = DuplicateNameResolver()

    /** 导入过程中需要弹「歌单重名」对话框时非 null */
    val pendingConflict: StateFlow<PlaylistNameConflict?> = duplicateNameResolver.pendingConflict

    /** 对话框里点了某个选项：唤醒挂起中的导入协程 */
    fun resolveDuplicate(choice: DuplicateNameChoice) = duplicateNameResolver.resolve(choice)

    /** 对话框被返回键 / 点击外部关掉：按取消处理 */
    fun dismissDuplicateDialog() = duplicateNameResolver.dismissAsCancelled()

    init {
        loadFavFolders()
    }

    fun onTabSelected(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    // ---------------- 收藏夹 ----------------

    fun loadFavFolders() {
        if (_uiState.value.favLoading) return
        _uiState.update { it.copy(favLoading = true, favError = null) }
        viewModelScope.launch {
            when (val result = importRepository.loadFavFolders()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        favLoading = false,
                        favFolders = result.data,
                        favError = null,
                        selectedFolderIds = it.selectedFolderIds.intersect(result.data.map { f -> f.id }.toSet()),
                    )
                }

                is ApiResult.Error -> _uiState.update {
                    it.copy(favLoading = false, favError = result.failure.userMessage)
                }
            }
        }
    }

    fun toggleFolder(folderId: Long) {
        _uiState.update { state ->
            val selected = state.selectedFolderIds.toMutableSet()
            if (!selected.add(folderId)) selected.remove(folderId)
            state.copy(selectedFolderIds = selected)
        }
    }

    fun selectAllFolders() {
        _uiState.update { it.copy(selectedFolderIds = it.favFolders.map { f -> f.id }.toSet()) }
    }

    fun clearFolderSelection() {
        _uiState.update { it.copy(selectedFolderIds = emptySet()) }
    }

    fun startImportFavorites() {
        val selected = _uiState.value.favFolders.filter { it.id in _uiState.value.selectedFolderIds }
        if (selected.isEmpty()) {
            viewModelScope.launch { _messages.send("请先勾选要导入的收藏夹") }
            return
        }
        _uiState.update { it.copy(lastTitle = "收藏夹导入") }
        runImport(
            importRepository.importFavorites(
                folders = selected,
                onNameConflict = { duplicateNameResolver.awaitChoice(it) },
            ),
        )
    }

    // ---------------- 稍后再看 ----------------

    fun startImportToView() {
        _uiState.update { it.copy(lastTitle = "稍后再看") }
        runImport(
            importRepository.importToView(
                onNameConflict = { duplicateNameResolver.awaitChoice(it) },
            ),
        )
    }

    // ---------------- UP 主投稿 ----------------

    fun onUploadMidChange(value: String) {
        _uiState.update { it.copy(uploadMid = value) }
    }

    fun startImportUploads() {
        val raw = _uiState.value.uploadMid.trim()
        if (raw.isEmpty()) {
            viewModelScope.launch { _messages.send("请输入 UP 主的 mid 或空间链接") }
            return
        }
        val mid = BiliLinkParser.parseMid(raw)
        if (mid == null) {
            viewModelScope.launch { _messages.send("没认出 mid，请输入数字 UID 或 space.bilibili.com/xxx 链接") }
            return
        }
        _uiState.update { it.copy(lastTitle = "UP主投稿") }
        runImport(
            importRepository.importUploads(
                mid = mid,
                onNameConflict = { duplicateNameResolver.awaitChoice(it) },
            ),
        )
    }

    // ---------------- 合集 / 分P ----------------

    fun onVideoInputChange(value: String) {
        _uiState.update { it.copy(videoInput = value) }
    }

    fun startImportVideo() {
        val raw = _uiState.value.videoInput.trim()
        if (raw.isEmpty()) {
            viewModelScope.launch { _messages.send("请粘贴 BV 号 / AV 号 / 分享链接") }
            return
        }
        _uiState.update { it.copy(lastTitle = "合集 / 分P 导入") }
        runImport(
            importRepository.importByVideoInput(
                rawInput = raw,
                onNameConflict = { duplicateNameResolver.awaitChoice(it) },
            ),
        )
    }

    // ---------------- 公共 ----------------

    fun cancelImport() {
        // 导入可能正卡在重名对话框上（导入协程挂在 awaitChoice）：先取消任务再收掉对话框，
        // 避免只关掉弹窗、协程却永远挂着。
        val job = importJob
        if (job != null && job.isActive) {
            job.cancel()
            importJob = null
        }
        duplicateNameResolver.dismissAsCancelled()
        _uiState.update { it.copy(running = false, progressText = "") }
        viewModelScope.launch { _messages.send("已取消导入（已导入的部分会保留）") }
    }

    fun dismissReport() {
        _uiState.update { it.copy(lastReport = null) }
    }

    private fun runImport(flow: Flow<ImportProgress>) {
        if (_uiState.value.running) return
        importJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    running = true,
                    lastReport = null,
                    progressCurrent = 0,
                    progressTotal = 0,
                    progressText = "准备中…",
                )
            }
            try {
                flow.collect { progress ->
                    when (progress) {
                        is ImportProgress.Preparing -> _uiState.update {
                            it.copy(progressText = progress.message)
                        }

                        is ImportProgress.Working -> _uiState.update {
                            it.copy(
                                progressText = progress.message,
                                progressCurrent = progress.current,
                                progressTotal = progress.total,
                            )
                        }

                        is ImportProgress.Finished -> {
                            _uiState.update { it.copy(lastReport = progress.report) }
                            _messages.send("导入完成：${progress.report.summary}")
                        }

                        is ImportProgress.Failed -> {
                            if (progress.riskControlled) {
                                _uiState.update { state ->
                                    state.copy(uploadBlocked = true, uploadBlockReason = progress.message)
                                }
                            }
                            _messages.send(progress.message)
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(running = false, progressText = "") }
                importJob = null
            }
        }
    }
}
