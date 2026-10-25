package com.bilimusic.app.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilimusic.app.domain.model.ImportReport
import com.bilimusic.app.ui.components.EmptyState
import com.bilimusic.app.ui.components.ErrorState
import com.bilimusic.app.ui.components.LoadingState

private val tabs = listOf("收藏", "稍后", "UP主", "链接")

/**
 * FR-7 页面 3：导入页。4 个来源各一个 Tab。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入歌单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = uiState.selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { viewModel.onTabSelected(index) },
                        text = { Text(title) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (uiState.selectedTab) {
                    0 -> FavoritesTab(uiState = uiState, viewModel = viewModel)
                    1 -> ToViewTab(uiState = uiState, viewModel = viewModel)
                    2 -> UploadTab(uiState = uiState, viewModel = viewModel)
                    else -> VideoTab(uiState = uiState, viewModel = viewModel)
                }
            }

            if (uiState.running) {
                RunningPanel(
                    text = uiState.progressText,
                    current = uiState.progressCurrent,
                    total = uiState.progressTotal,
                    fraction = uiState.progressFraction,
                    onCancel = viewModel::cancelImport,
                )
            }

            uiState.lastReport?.let { report ->
                ReportPanel(
                    title = uiState.lastTitle,
                    report = report,
                    onOpenPlaylist = { onOpenPlaylist(report.playlistId) },
                    onDismiss = viewModel::dismissReport,
                )
            }
        }
    }
}

// ---------------- Tab 1：收藏夹 ----------------

@Composable
private fun FavoritesTab(
    uiState: ImportUiState,
    viewModel: ImportViewModel,
) {
    when {
        uiState.favLoading -> LoadingState(text = "正在读取收藏夹列表…")

        uiState.favError != null -> ErrorState(
            message = uiState.favError,
            onRetry = viewModel::loadFavFolders,
        )

        uiState.favFolders.isEmpty() -> EmptyState(
            icon = Icons.Outlined.LibraryMusic,
            title = "没有读到收藏夹",
            description = "可能这个账号还没有创建过收藏夹。可以先去 B 站网页版收藏几个视频，或者用「稍后再看 / 合集」方式导入。",
            actionLabel = "重新读取",
            onAction = viewModel::loadFavFolders,
        )

        else -> Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 ${uiState.selectedFolderIds.size} / ${uiState.favFolders.size} 个收藏夹",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::selectAllFolders) { Text("全选") }
                TextButton(onClick = viewModel::clearFolderSelection) { Text("全不选") }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(uiState.favFolders, key = { it.id }) { folder ->
                    val checked = folder.id in uiState.selectedFolderIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleFolder(folder.id) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { viewModel.toggleFolder(folder.id) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = folder.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${folder.mediaCount} 个视频",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (folder.isPrivate) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = "私密收藏夹",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Button(
                onClick = viewModel::startImportFavorites,
                enabled = uiState.canImportFavorites,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    if (uiState.selectedFolderIds.isEmpty()) {
                        "请至少选择 1 个收藏夹"
                    } else {
                        "导入选中的 ${uiState.selectedFolderIds.size} 个收藏夹"
                    },
                )
            }
        }
    }
}

// ---------------- Tab 2：稍后再看 ----------------

@Composable
private fun ToViewTab(
    uiState: ImportUiState,
    viewModel: ImportViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("稍后再看", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "把 B 站「稍后再看」里的全部视频导入成一个歌单。已经是失效视频的条目会被自动跳过，导入结束会给出报告。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = viewModel::startImportToView,
            enabled = !uiState.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("导入稍后再看")
        }
    }
}

// ---------------- Tab 3：UP主投稿 ----------------

@Composable
private fun UploadTab(
    uiState: ImportUiState,
    viewModel: ImportViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("UP 主投稿", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "输入 UP 主的 UID（mid）或空间链接，例如 12345678 或 space.bilibili.com/12345678。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.uploadBlocked) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(
                            text = "该来源受 B 站风控限制",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = uiState.uploadBlockReason
                                ?: "请改用「收藏夹」或「合集」导入，这两条路径不受影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = viewModel::loadFavFolders) {
                            Text("去用收藏夹导入")
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = uiState.uploadMid,
            onValueChange = viewModel::onUploadMidChange,
            label = { Text("UP 主 mid 或空间链接") },
            singleLine = true,
            enabled = !uiState.uploadBlocked,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = viewModel::startImportUploads,
            enabled = uiState.canImportUploads,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    uiState.uploadBlocked -> "该来源已被风控限制"
                    uiState.uploadMid.isBlank() -> "请先输入 UP 主 mid"
                    else -> "导入该 UP 主的投稿"
                },
            )
        }

        Text(
            text = "说明：这个接口风控最严。App 遇到 -352 / -412 会立刻停手并保留已导入的部分，不会反复重试打接口。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- Tab 4：合集 / 分P ----------------

@Composable
private fun VideoTab(
    uiState: ImportUiState,
    viewModel: ImportViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("链接导入", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "把下面任意一种内容粘贴进来，App 会自动识别：\n" +
                "· 单曲：BV 号 / AV 号 / 视频链接 / b23.tv 短链\n" +
                "· 合集：合集里任意一集的链接（会拉取该合集全部集数）\n" +
                "· 多 P 视频：导入全部分 P\n" +
                "· 歌单分享链接：以 bm://pl/ 开头，会新建一个歌单并导入其中的歌曲",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = uiState.videoInput,
            onValueChange = viewModel::onVideoInputChange,
            label = { Text("BV 号 / AV 号 / 视频链接 / 歌单分享链接") },
            placeholder = { Text("BV1xx411c7mD\nhttps://b23.tv/xxxxxx\nbm://pl/v1/…") },
            minLines = 3,
            enabled = !uiState.running,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::startImportVideo,
            enabled = uiState.canImportVideo,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    uiState.videoInput.isBlank() -> "请先粘贴内容"
                    uiState.videoInput.trim().startsWith("bm://pl/") -> "导入这个歌单"
                    else -> "导入"
                },
            )
        }
    }
}

// ---------------- 进度 / 报告 ----------------

@Composable
private fun RunningPanel(
    text: String,
    current: Int,
    total: Int,
    fraction: Float?,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(
                    text = if (total > 0) "第 $current / 共 $total 条" else "准备中…",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消") }
            }
            Spacer(Modifier.height(8.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ReportPanel(
    title: String,
    report: ImportReport,
    onOpenPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("$title · 导入完成", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "歌单「${report.playlistTitle}」",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "成功 ${report.success} 条",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (report.skippedInvalid > 0) {
                Text(
                    text = "跳过失效 ${report.skippedInvalid} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.skippedDuplicate > 0) {
                Text(
                    text = "重复（已在歌单里）跳过 ${report.skippedDuplicate} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.failed > 0) {
                Text(
                    text = "读取失败 ${report.failed} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenPlaylist) { Text("去看看这个歌单") }
                OutlinedButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
