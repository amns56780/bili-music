package com.bilimusic.app.ui.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.bilimusic.app.util.PlaylistShareCodec
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bilimusic.app.data.repository.DownloadInfo
import com.bilimusic.app.data.repository.DownloadStatus
import com.bilimusic.app.domain.model.Song
import com.bilimusic.app.domain.model.formatDuration
import com.bilimusic.app.ui.components.ConfirmDialog
import com.bilimusic.app.ui.components.EmptyState
import com.bilimusic.app.ui.components.LoadingState
import com.bilimusic.app.ui.components.TextInputDialog
import com.bilimusic.app.ui.local.LocalBadge

/**
 * 多选模式的底部操作栏：显示已选数量 + 「复制到歌单」「移动到歌单」。
 * 没选任何曲目时两个按钮置灰（不允许点了没反应）。
 */
@Composable
private fun BatchActionBar(
    selectedCount: Int,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "已选 $selectedCount 首",
                style = MaterialTheme.typography.titleSmall,
                color = if (selectedCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (selectedCount == 0) {
                Text(
                    text = "请至少选择 1 首",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCopy,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("复制到歌单")
                }
                OutlinedButton(
                    onClick = onMove,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.DriveFileMove,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("移动到歌单")
                }
            }
        }
    }
}

/** 选择目标歌单（复制/移动的落点） */
@Composable
private fun BatchTargetDialog(
    state: BatchTargetUiState,
    onPick: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val verb = if (state.action == SongBatchAction.COPY) "复制" else "移动"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把选中的曲目${verb}到哪个歌单？") },
        text = {
            Column {
                Text(
                    text = if (state.action == SongBatchAction.MOVE) {
                        "「移动」会把曲目从当前歌单移除；目标歌单里已存在的会跳过重复。"
                    } else {
                        "「复制」不动当前歌单；目标歌单里已存在的会跳过重复。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist.id, playlist.title) },
                            headlineContent = {
                                Text(
                                    text = playlist.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text("${playlist.songCount} 首") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * FR-9 下载状态指示：未下载（灰下载图标，点了就下载）/ 下载中（进度环）/ 已下载（对勾） / 失败（红图标）
 */
@Composable
private fun DownloadStatusIndicator(
    info: DownloadInfo,
    onDownload: () -> Unit,
) {
    when (info.status) {
        DownloadStatus.DOWNLOADING -> {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { info.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        DownloadStatus.QUEUED -> {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        DownloadStatus.COMPLETED -> {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.DownloadDone,
                    contentDescription = "已下载",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        else -> {
            IconButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Outlined.DownloadForOffline,
                    contentDescription = if (info.status == DownloadStatus.FAILED) "重新下载" else "下载",
                    tint = if (info.status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * FR-7 页面 4：歌单详情页。
 * Phase 1 提供：封面首图 + 曲目数 + 总时长 + 真实曲目列表 + 单曲移除 + 歌单重命名/删除。
 * Phase 4 会在此页顶部补上「播放全部 / 随机播放」，Phase 6 补「选集」入口，Phase 9 补「全部下载」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenCollection: (String) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedSongIds by viewModel.selectedSongIds.collectAsStateWithLifecycle()
    val batchTarget by viewModel.batchTarget.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Song?>(null) }
    var showCoverPicker by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlaylistDetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
                PlaylistDetailEvent.PlaylistDeleted -> onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                // 多选模式：顶部变成「已选 n 首」+ 全选/反选
                TopAppBar(
                    title = { Text("已选 ${selectedSongIds.size} 首") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::exitSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::selectAllSongs) { Text("全选") }
                        TextButton(onClick = viewModel::invertSelection) { Text("反选") }
                    },
                )
            } else {
                TopAppBar(
                title = {
                    Text(
                        text = uiState.playlist?.title ?: "歌单",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // FR-9：整单离线下载
                    IconButton(
                        onClick = viewModel::downloadAll,
                        enabled = uiState.songs.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.DownloadForOffline, contentDescription = "全部下载")
                    }
                    IconButton(
                        onClick = viewModel::openAddSongDialog,
                        enabled = uiState.playlist != null,
                    ) {
                        Icon(Icons.Outlined.PlaylistAdd, contentDescription = "添加单曲")
                    }
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            enabled = uiState.playlist != null,
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("重命名歌单") },
                                onClick = {
                                    showMoreMenu = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设置封面") },
                                onClick = {
                                    showMoreMenu = false
                                    showCoverPicker = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("分享歌单") },
                                onClick = {
                                    showMoreMenu = false
                                    val pl = uiState.playlist
                                    if (pl != null) {
                                        val link = PlaylistShareCodec.encode(
                                            pl.title,
                                            uiState.songs.map { it.bvid },
                                        )
                                        clipboardManager.setText(AnnotatedString(link))
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "歌单链接已复制，发给另一台设备粘贴导入（共 " +
                                                    uiState.songs.size + " 首）",
                                            )
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除歌单") },
                                onClick = {
                                    showMoreMenu = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                },
            )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val playlist = uiState.playlist
            when {
                uiState.loading -> LoadingState()

                playlist == null -> EmptyState(
                    icon = Icons.Outlined.MusicNote,
                    title = "歌单不存在",
                    description = "这个歌单可能已经被删除了。",
                    actionLabel = "返回歌单列表",
                    onAction = onBack,
                )

                else -> LazyColumn(
                    // 平板/横屏下别把每一行拉成一整屏宽，居中限宽更好读
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp),
                    // 多选时底部有操作栏，留出空间免得最后几首被挡住
                    contentPadding = PaddingValues(bottom = if (selectionMode) 148.dp else 24.dp),
                ) {
                    item {
                        PlaylistHeader(
                            playlist = playlist,
                            songCount = uiState.songs.size,
                            totalDurationMs = uiState.totalDurationMs,
                            onPlayAll = {
                                if (viewModel.playAll(shuffle = false)) onOpenPlayer()
                            },
                            onShufflePlay = {
                                if (viewModel.playAll(shuffle = true)) onOpenPlayer()
                            },
                        )
                    }

                    if (uiState.songs.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.MusicNote,
                                title = "这个歌单还没有歌",
                                description = "回到歌单列表点「导入」把 B 站收藏夹导进来，或者在下一阶段用「粘贴 BV 号」加单曲。",
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = uiState.songs,
                            key = { _, song -> song.id },
                        ) { index, song ->
                            SongRow(
                                index = index + 1,
                                song = song,
                                downloadInfo = viewModel.downloadStatusOf(song, downloadStates),
                                selectionMode = selectionMode,
                                selected = song.id in selectedSongIds,
                                onClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(song.id)
                                    } else if (viewModel.playAt(index)) {
                                        onOpenPlayer()
                                    }
                                },
                                onLongClick = { viewModel.enterSelection(song.id) },
                                onOpenCollection = {
                                    song.collectionKey?.let(onOpenCollection)
                                },
                                onDownload = { viewModel.downloadSong(song) },
                                onRemoveDownload = { viewModel.removeDownload(song) },
                                onRemove = { removeTarget = song },
                            )
                            if (index != uiState.songs.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 多选模式的底部操作栏：批量复制 / 移动到其他歌单
            if (selectionMode) {
                BatchActionBar(
                    selectedCount = selectedSongIds.size,
                    onCopy = { viewModel.openBatchTarget(SongBatchAction.COPY) },
                    onMove = { viewModel.openBatchTarget(SongBatchAction.MOVE) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // 歌单间批量复制 / 移动：选择目标歌单
    batchTarget?.let { target ->
        BatchTargetDialog(
            state = target,
            onPick = { id, title -> viewModel.applyBatchTarget(id, title) },
            onDismiss = viewModel::closeBatchTarget,
        )
    }

    if (showCoverPicker) {
        AlertDialog(
            onDismissRequest = { showCoverPicker = false },
            title = { Text("设置歌单封面") },
            text = {
                Column {
                    Text(
                        text = "从歌单里的曲目选一张视频封面当歌单封面",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.clearCover()
                                        showCoverPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("恢复默认封面")
                            }
                        }
                        items(
                            uiState.songs.filter { !it.coverUrl.isNullOrBlank() },
                            key = { it.id },
                        ) { song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setCoverFromSong(song)
                                        showCoverPicker = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = song.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = song.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCoverPicker = false }) { Text("关闭") }
            },
        )
    }

    if (showRenameDialog) {
        TextInputDialog(
            title = "重命名歌单",
            label = "歌单名称",
            initialValue = uiState.playlist?.title.orEmpty(),
            confirmLabel = "保存",
            onConfirm = { title ->
                showRenameDialog = false
                viewModel.renamePlaylist(title)
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "删除歌单",
            message = "确定删除这个歌单吗？${uiState.songs.size} 首曲目与选集勾选记录会一并删除，且无法恢复。",
            confirmLabel = "删除",
            onConfirm = {
                showDeleteDialog = false
                viewModel.deletePlaylist()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    removeTarget?.let { song ->
        ConfirmDialog(
            title = "从歌单移除",
            message = "确定移除「${song.title}」吗？只影响这个歌单，不会删除原视频，也不会影响其他歌单里的同一首歌。",
            confirmLabel = "移除",
            onConfirm = {
                removeTarget = null
                viewModel.removeSong(song)
            },
            onDismiss = { removeTarget = null },
        )
    }

    val addSongState by viewModel.addSongState.collectAsStateWithLifecycle()
    if (addSongState.dialogOpen) {
        AddSongDialog(
            state = addSongState,
            onValueChange = viewModel::onAddSongInputChange,
            onConfirm = viewModel::submitAddSong,
            onDismiss = viewModel::closeAddSongDialog,
        )
    }
}

/** 粘贴 BV / AV / 分享链接 往当前歌单加单曲（FR-2 第 5 条） */
@Composable
private fun AddSongDialog(
    state: AddSongUiState,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加单曲") },
        text = {
            Column {
                Text(
                    text = "粘贴 BV 号、AV 号、b23.tv 短链，或整段分享文案都行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onValueChange,
                    label = { Text("BV 号 / AV 号 / 链接") },
                    placeholder = { Text("BV1xx411c7mD 或 https://b23.tv/xxxxxx") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.submitting) {
                    Text(
                        text = "正在读取视频信息…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canSubmit) {
                Text(if (state.submitting) "添加中…" else "添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PlaylistHeader(
    playlist: com.bilimusic.app.domain.model.Playlist,
    songCount: Int,
    totalDurationMs: Long,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = playlist.coverUrl
            if (cover.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                )
            } else {
                AsyncImage(
                    model = cover,
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$songCount 首 · 总时长 ${formatDuration(totalDurationMs)} · 来源：${playlist.sourceType.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPlayAll,
                    enabled = songCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("播放全部")
                }
                OutlinedButton(
                    onClick = onShufflePlay,
                    enabled = songCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("随机播放")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    index: Int,
    song: Song,
    downloadInfo: DownloadInfo,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenCollection: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        headlineContent = {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = song.upperName.ifBlank { "未知UP主" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.isLocal) {
                    LocalBadge()
                }
                if (song.episodeCount > 1) {
                    Text(
                        text = "合集 · 第 ${song.pageIndex} 集 / 共 ${song.episodeCount} 集",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (song.isInvalid) {
                    Text(
                        text = "已失效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        leadingContent = {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadStatusIndicator(
                    info = downloadInfo,
                    onDownload = onDownload,
                )
                Text(
                    text = song.durationText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "曲目操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (downloadInfo.status == DownloadStatus.COMPLETED) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = null)
                                },
                                text = { Text("删除离线缓存") },
                                onClick = {
                                    menuExpanded = false
                                    onRemoveDownload()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Outlined.DownloadForOffline, contentDescription = null)
                                },
                                text = {
                                    Text(
                                        when (downloadInfo.status) {
                                            DownloadStatus.QUEUED -> "已在下载队列中"
                                            DownloadStatus.DOWNLOADING -> "正在下载…"
                                            DownloadStatus.FAILED -> "重新下载"
                                            else -> "下载（离线播放）"
                                        },
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDownload()
                                },
                            )
                        }
                        if (song.collectionKey != null && song.episodeCount > 1) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Outlined.PlaylistPlay, contentDescription = null)
                                },
                                text = { Text("选集（共 ${song.episodeCount} 集）") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenCollection()
                                },
                            )
                        }
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Icons.Outlined.PlaylistRemove, contentDescription = null)
                            },
                            text = { Text("从歌单移除") },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        },
    )
}
