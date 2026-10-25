package com.bilimusic.app.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.player.PlaybackConnection
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.repository.DownloadInfo
import com.bilimusic.app.data.repository.DownloadRepository
import com.bilimusic.app.data.repository.DownloadStatus
import com.bilimusic.app.data.repository.ImportRepository
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.domain.model.Playlist
import com.bilimusic.app.domain.model.Song
import com.bilimusic.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailUiState(
    val loading: Boolean = true,
    val playlist: Playlist? = null,
    val songs: List<Song> = emptyList(),
) {
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
}

sealed interface PlaylistDetailEvent {
    data class Message(val text: String) : PlaylistDetailEvent
    data object PlaylistDeleted : PlaylistDetailEvent
}

/** 「粘贴 BV 号加单曲」对话框的状态 */
data class AddSongUiState(
    val dialogOpen: Boolean = false,
    val input: String = "",
    val submitting: Boolean = false,
) {
    val canSubmit: Boolean get() = !submitting && input.isNotBlank()
}

/** 歌单间批量搬歌的动作 */
enum class SongBatchAction { COPY, MOVE }

/** 选择目标歌单的对话框状态 */
data class BatchTargetUiState(
    val action: SongBatchAction,
    val playlists: List<Playlist> = emptyList(),
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val importRepository: ImportRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackConnection: PlaybackConnection,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.get<Long>(Routes.ARG_PLAYLIST_ID) ?: -1L

    /** 当前音质档位对应的缓存 key 后缀（FR-9：key = bvid-cid-音质） */
    private val qualityIdFlow = MutableStateFlow(0)

    /** cacheKey → 下载状态，列表项显示「未下载 / 下载中 / 已下载」 */
    val downloadStates: StateFlow<Map<String, DownloadInfo>> = combine(
        downloadRepository.downloads,
        qualityIdFlow,
    ) { records, qualityId ->
        records.associate { record ->
            record.cacheKey to downloadRepository.statusOf(record.cacheKey, records)
        }.filterKeys { it.endsWith("-$qualityId") }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
    )

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        repository.observePlaylist(playlistId),
        repository.observeSongs(playlistId),
    ) { playlist, songs ->
        PlaylistDetailUiState(loading = false, playlist = playlist, songs = songs)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistDetailUiState(),
    )

    private val _events = Channel<PlaylistDetailEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistDetailEvent> = _events.receiveAsFlow()

    private val _addSongState = MutableStateFlow(AddSongUiState())
    val addSongState: StateFlow<AddSongUiState> = _addSongState.asStateFlow()

    // ---------------- 多选 + 歌单间复制/移动 ----------------

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSongIds: StateFlow<Set<Long>> = _selectedSongIds.asStateFlow()

    private val _batchTarget = MutableStateFlow<BatchTargetUiState?>(null)
    val batchTarget: StateFlow<BatchTargetUiState?> = _batchTarget.asStateFlow()

    /** 长按曲目进入多选，并把这一首勾上 */
    fun enterSelection(songId: Long) {
        _selectionMode.value = true
        _selectedSongIds.value = setOf(songId)
    }

    fun toggleSelection(songId: Long) {
        val current = _selectedSongIds.value
        _selectedSongIds.value = if (songId in current) current - songId else current + songId
    }

    fun selectAllSongs() {
        _selectedSongIds.value = uiState.value.songs.map { it.id }.toSet()
    }

    fun invertSelection() {
        val all = uiState.value.songs.map { it.id }.toSet()
        _selectedSongIds.value = all - _selectedSongIds.value
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedSongIds.value = emptySet()
    }

    /** 打开「选择目标歌单」对话框（排除当前歌单 —— 复制/移动到自己没意义） */
    fun openBatchTarget(action: SongBatchAction) {
        if (_selectedSongIds.value.isEmpty()) {
            viewModelScope.launch { _events.send(PlaylistDetailEvent.Message("请先选择曲目")) }
            return
        }
        viewModelScope.launch {
            val candidates = repository.observePlaylists().first().filter { it.id != playlistId }
            if (candidates.isEmpty()) {
                _events.send(PlaylistDetailEvent.Message("还没有其他歌单，先去歌单列表新建一个"))
                return@launch
            }
            _batchTarget.value = BatchTargetUiState(action = action, playlists = candidates)
        }
    }

    fun closeBatchTarget() {
        _batchTarget.value = null
    }

    /** 执行批量复制 / 移动 */
    fun applyBatchTarget(targetPlaylistId: Long, targetTitle: String) {
        val target = _batchTarget.value ?: return
        val ids = _selectedSongIds.value
        if (ids.isEmpty()) return
        _batchTarget.value = null
        viewModelScope.launch {
            val result = when (target.action) {
                SongBatchAction.COPY -> repository.copySongsTo(playlistId, targetPlaylistId, ids)
                SongBatchAction.MOVE -> repository.moveSongsTo(playlistId, targetPlaylistId, ids)
            }
            val verb = if (target.action == SongBatchAction.COPY) "复制" else "移动"
            exitSelection()
            _events.send(
                PlaylistDetailEvent.Message(
                    buildString {
                        append("已$verb ${result.inserted} 首到「$targetTitle」")
                        if (result.skippedAsDuplicate > 0) {
                            append("；${result.skippedAsDuplicate} 首已在目标歌单，跳过重复")
                        }
                    },
                ),
            )
        }
    }

    init {
        downloadRepository.syncIfNeeded()
        viewModelScope.launch { qualityIdFlow.value = downloadRepository.currentQualityId() }
    }

    // ---------------- FR-9 离线下载 ----------------

    /** 下载单曲 */
    fun downloadSong(song: Song) {
        viewModelScope.launch {
            downloadRepository.enqueueSongs(listOf(song))
            _events.send(PlaylistDetailEvent.Message("已加入下载队列：${song.title}"))
        }
    }

    /** 批量下载整个歌单 */
    fun downloadAll() {
        val songs = uiState.value.songs
        if (songs.isEmpty()) {
            viewModelScope.launch { _events.send(PlaylistDetailEvent.Message("这个歌单还没有曲目")) }
            return
        }
        viewModelScope.launch {
            downloadRepository.enqueueSongs(songs)
            _events.send(
                PlaylistDetailEvent.Message("已加入下载队列：${songs.size} 首（并发 2，慢慢来）"),
            )
        }
    }

    /** 删除某首歌的离线缓存 */
    fun removeDownload(song: Song) {
        viewModelScope.launch {
            val qualityId = qualityIdFlow.value
            downloadRepository.removeDownload(downloadRepository.cacheKeyOf(song, qualityId))
            _events.send(PlaylistDetailEvent.Message("已删除缓存：${song.title}"))
        }
    }

    /** 列表项要显示的状态（未下载时返回 NOT_DOWNLOADED） */
    fun downloadStatusOf(song: Song, states: Map<String, DownloadInfo>): DownloadInfo {
        val key = downloadRepository.cacheKeyOf(song, qualityIdFlow.value)
        return states[key] ?: DownloadInfo(key, DownloadStatus.NOT_DOWNLOADED, 0f, 0L)
    }

    // ---------------- 播放（FR-4） ----------------

    /** 播放全部；shuffle = true 时按固定 seed 打乱 */
    fun playAll(shuffle: Boolean = false): Boolean {
        val playable = uiState.value.songs.filterNot { it.isInvalid }
        if (playable.isEmpty()) {
            viewModelScope.launch { _events.send(PlaylistDetailEvent.Message("这个歌单里没有可播放的曲目")) }
            return false
        }
        playbackConnection.playSongs(playable, 0, shuffle = shuffle)
        return true
    }

    /** 点某一首开始播（队列仍是整个歌单，且保持原顺序） */
    fun playAt(index: Int): Boolean {
        val songs = uiState.value.songs
        val song = songs.getOrNull(index) ?: return false
        if (song.isInvalid) {
            viewModelScope.launch {
                _events.send(PlaylistDetailEvent.Message("「${song.title}」已失效，无法播放"))
            }
            return false
        }
        val playable = songs.filterNot { it.isInvalid }
        val queueIndex = playable.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playbackConnection.playSongs(playable, queueIndex)
        return true
    }

    // ---------------- 粘贴 BV / AV / 链接 加单曲 ----------------

    fun openAddSongDialog() {
        _addSongState.update { it.copy(dialogOpen = true, input = "") }
    }

    fun closeAddSongDialog() {
        _addSongState.update { it.copy(dialogOpen = false, submitting = false) }
    }

    fun onAddSongInputChange(value: String) {
        _addSongState.update { it.copy(input = value) }
    }

    fun submitAddSong() {
        val raw = _addSongState.value.input.trim()
        if (raw.isEmpty()) {
            viewModelScope.launch { _events.send(PlaylistDetailEvent.Message("请先粘贴 BV 号 / AV 号 / 链接")) }
            return
        }
        if (_addSongState.value.submitting) return
        viewModelScope.launch {
            _addSongState.update { it.copy(submitting = true) }
            when (val result = importRepository.addSongToPlaylist(playlistId, raw)) {
                is ApiResult.Success -> {
                    _addSongState.update { it.copy(submitting = false, dialogOpen = false, input = "") }
                    _events.send(PlaylistDetailEvent.Message(result.data.message))
                }

                is ApiResult.Error -> {
                    _addSongState.update { it.copy(submitting = false) }
                    _events.send(PlaylistDetailEvent.Message(result.failure.userMessage))
                }
            }
        }
    }

    /** 从歌单移除单曲（用户要求：能去掉某个特定分P/单曲） */
    fun removeSong(song: Song) {
        viewModelScope.launch {
            repository.removeSong(song.id)
            _events.send(PlaylistDetailEvent.Message("已从歌单移除「${song.title}」"))
        }
    }

    /** 用歌单里某首歌的视频封面作为歌单封面（对齐桌面端的「从歌单视频选封面」） */
    fun setCoverFromSong(song: Song) {
        viewModelScope.launch {
            val cover = song.coverUrl
            if (cover.isNullOrBlank()) {
                _events.send(PlaylistDetailEvent.Message("这首歌没有封面，换一首试试"))
            } else {
                repository.updatePlaylistCover(playlistId, cover)
                _events.send(PlaylistDetailEvent.Message("封面已更新"))
            }
        }
    }

    /** 恢复默认封面 */
    fun clearCover() {
        viewModelScope.launch {
            repository.updatePlaylistCover(playlistId, null)
            _events.send(PlaylistDetailEvent.Message("已恢复默认封面"))
        }
    }

    fun renamePlaylist(rawTitle: String) {
        val title = rawTitle.trim()
        viewModelScope.launch {
            val current = repository.getPlaylist(playlistId)
            when {
                current == null -> _events.send(PlaylistDetailEvent.Message("歌单不存在或已被删除"))
                title.isEmpty() -> _events.send(PlaylistDetailEvent.Message("歌单名不能为空"))
                current.title == title -> _events.send(PlaylistDetailEvent.Message("歌单名没有变化"))
                repository.playlistTitleExists(title) ->
                    _events.send(PlaylistDetailEvent.Message("已存在同名歌单「$title」"))

                else -> {
                    repository.renamePlaylist(playlistId, title)
                    _events.send(PlaylistDetailEvent.Message("已重命名为「$title」"))
                }
            }
        }
    }

    fun deletePlaylist() {
        viewModelScope.launch {
            val current = repository.getPlaylist(playlistId)
            repository.deletePlaylist(playlistId)
            _events.send(
                PlaylistDetailEvent.Message(
                    if (current != null) "已删除歌单「${current.title}」" else "歌单已删除",
                ),
            )
            _events.send(PlaylistDetailEvent.PlaylistDeleted)
        }
    }
}
