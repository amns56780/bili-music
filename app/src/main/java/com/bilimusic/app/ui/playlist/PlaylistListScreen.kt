package com.bilimusic.app.ui.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bilimusic.app.domain.model.Playlist
import com.bilimusic.app.ui.components.ConfirmDialog
import com.bilimusic.app.ui.components.DuplicateNameDialog
import com.bilimusic.app.ui.components.EmptyState
import com.bilimusic.app.ui.components.LoadingState
import com.bilimusic.app.ui.components.TextInputDialog

/**
 * FR-7 页面 2：歌单列表页（首页）。
 * 右上角「新建歌单」；Phase 3 会在旁边加「导入」入口。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistListScreen(
    onOpenPlaylist: (Long) -> Unit,
    onOpenImport: () -> Unit,
    onOpenLocalMusic: () -> Unit,
    viewModel: PlaylistListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingConflict by viewModel.pendingConflict.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的歌单") },
                actions = {
                    IconButton(onClick = onOpenLocalMusic) {
                        Icon(Icons.Outlined.LibraryMusic, contentDescription = "本地音乐")
                    }
                    IconButton(onClick = onOpenImport) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = "导入")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建歌单")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                PlaylistListUiState.Loading -> LoadingState()

                is PlaylistListUiState.Ready -> {
                    if (state.playlists.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.LibraryMusic,
                            title = "还没有歌单",
                            description = "可以导入 B 站收藏夹 / 稍后再看 / 合集，也可以先手动建一个空歌单再往里加歌。",
                            actionLabel = "导入 B 站收藏夹",
                            onAction = onOpenImport,
                            secondaryActionLabel = "新建空白歌单",
                            onSecondaryAction = { showCreateDialog = true },
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.playlists, key = { it.id }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onOpen = { onOpenPlaylist(playlist.id) },
                                    onRename = { renameTarget = playlist },
                                    onDelete = { deleteTarget = playlist },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        TextInputDialog(
            title = "新建歌单",
            label = "歌单名称",
            confirmLabel = "创建",
            onConfirm = { title ->
                showCreateDialog = false
                viewModel.createPlaylist(title)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "重命名歌单",
            label = "歌单名称",
            initialValue = target.title,
            confirmLabel = "保存",
            onConfirm = { title ->
                renameTarget = null
                viewModel.renamePlaylist(target.id, title)
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除歌单",
            message = "确定删除「${target.title}」吗？该歌单下的 ${target.songCount} 首曲目和选集勾选记录会一并删除，且无法恢复。",
            confirmLabel = "删除",
            onConfirm = {
                deleteTarget = null
                viewModel.deletePlaylist(target)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // 新建 / 重命名碰到重名：覆盖 / 自动重命名 / 取消
    pendingConflict?.let { conflict ->
        DuplicateNameDialog(
            conflict = conflict,
            onChoose = viewModel::resolveDuplicate,
            onDismiss = viewModel::dismissDuplicateDialog,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (playlist.coverUrl.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(8.dp),
                    )
                } else {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = playlist.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除歌单") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                )
                Text(
                    text = "${playlist.songCount} 首 · ${playlist.totalDurationText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = playlist.sourceType.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
