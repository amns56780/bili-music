package com.bilimusic.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.Playlist
import com.bilimusic.app.domain.model.PlaylistNameConflict
import com.bilimusic.app.domain.model.PlaylistNaming
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlaylistListUiState {
    data object Loading : PlaylistListUiState
    data class Ready(val playlists: List<Playlist>) : PlaylistListUiState
}

@HiltViewModel
class PlaylistListViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val uiState: StateFlow<PlaylistListUiState> = repository.observePlaylists()
        .map<List<Playlist>, PlaylistListUiState> { PlaylistListUiState.Ready(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaylistListUiState.Loading,
        )

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** 重名对话框的挂起 resolver（新建 / 重命名共用） */
    private val duplicateNameResolver = DuplicateNameResolver()

    /** 需要弹「歌单重名」对话框时非 null；Screen 用它显示覆盖 / 自动重命名 / 取消 */
    val pendingConflict: StateFlow<PlaylistNameConflict?> = duplicateNameResolver.pendingConflict

    /** 对话框里点了某个选项 */
    fun resolveDuplicate(choice: DuplicateNameChoice) = duplicateNameResolver.resolve(choice)

    /** 对话框被返回键 / 点击外部关掉 */
    fun dismissDuplicateDialog() = duplicateNameResolver.dismissAsCancelled()

    fun createPlaylist(rawTitle: String) {
        val title = PlaylistNaming.normalize(rawTitle)
        if (title == null) {
            viewModelScope.launch { _messages.send("歌单名不能为空") }
            return
        }
        viewModelScope.launch {
            val resolved = duplicateNameResolver.resolveTitleForWrite(
                desiredTitle = title,
                findConflict = { repository.findNameConflict(it) },
                overwrite = { repository.deleteByTitle(it.existingTitle) },
            ) ?: run {
                _messages.send("已取消创建歌单")
                return@launch
            }
            repository.createPlaylist(resolved.title)
            _messages.send(
                if (resolved.overwritten) {
                    "已覆盖同名歌单，创建「${resolved.title}」"
                } else {
                    "已创建歌单「${resolved.title}」"
                },
            )
        }
    }

    fun renamePlaylist(playlistId: Long, rawTitle: String) {
        val title = PlaylistNaming.normalize(rawTitle)
        viewModelScope.launch {
            if (title == null) {
                _messages.send("歌单名不能为空")
                return@launch
            }
            val current = repository.getPlaylist(playlistId)
            if (current == null) {
                _messages.send("歌单不存在或已被删除")
                return@launch
            }
            if (PlaylistNaming.normalize(current.title) == title) {
                _messages.send("歌单名没有变化")
                return@launch
            }
            val resolved = duplicateNameResolver.resolveTitleForWrite(
                desiredTitle = title,
                findConflict = { repository.findNameConflict(it, exceptId = playlistId) },
                overwrite = { repository.deleteByTitle(it.existingTitle) },
            ) ?: run {
                _messages.send("已取消重命名")
                return@launch
            }
            repository.renamePlaylist(playlistId, resolved.title)
            _messages.send(
                if (resolved.overwritten) {
                    "已覆盖同名歌单，重命名为「${resolved.title}」"
                } else {
                    "已重命名为「${resolved.title}」"
                },
            )
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist.id)
            _messages.send("已删除歌单「${playlist.title}」")
        }
    }

    /** 拖动排序：把第 fromIndex 个歌单移动到 toIndex */
    fun movePlaylist(fromIndex: Int, toIndex: Int) {
        val ready = uiState.value as? PlaylistListUiState.Ready ?: return
        val playlists = ready.playlists
        if (fromIndex !in playlists.indices || toIndex !in playlists.indices) return
        if (fromIndex == toIndex) return
        val reordered = playlists.toMutableList()
        val moved = reordered.removeAt(fromIndex)
        reordered.add(toIndex, moved)
        viewModelScope.launch {
            repository.reorderPlaylists(reordered.map { it.id })
        }
    }
}
