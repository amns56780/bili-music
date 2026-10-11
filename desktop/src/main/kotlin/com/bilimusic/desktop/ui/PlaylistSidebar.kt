package com.bilimusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilimusic.app.data.remote.dto.FavFolderDto
import com.bilimusic.desktop.data.ImportProgress
import com.bilimusic.desktop.data.StoredPlaylist
import com.bilimusic.desktop.data.StoredSong

/**
 * 歌单侧边栏（FR-2 / FR-3）：
 * 歌单列表 + 新建 + 导入（收藏夹 / 稍后再看）+ 导入进度。
 */
@Composable
fun PlaylistSidebar(
    playlists: List<StoredPlaylist>,
    selectedId: Long?,
    progress: ImportProgress?,
    favFolders: List<FavFolderDto>,
    onSelect: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onLoadFavFolders: () -> Unit,
    onImportFolder: (Long, String) -> Unit,
    onImportToView: () -> Unit,
    onOpenLocalMusic: () -> Unit,
    onImportSeason: (String) -> Unit,
    onImportSpace: (String) -> Unit,
    onDismissFavPicker: () -> Unit,
    onConsumeProgressError: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var showFavPicker by remember { mutableStateOf(false) }
    var showSeason by remember { mutableStateOf(false) }
    var seasonInput by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的歌单（${playlists.size}）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建歌单")
                }
                IconButton(
                    onClick = {
                        showFavPicker = true
                        onLoadFavFolders()
                    },
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "从收藏夹导入")
                }
            }

            TextButton(onClick = onImportToView, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("导入「稍后再看」")
            }

            TextButton(onClick = onOpenLocalMusic, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("本地音乐（扫描电脑）")
            }

            TextButton(onClick = { showSeason = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PlaylistAdd, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("导入 UP主投稿 / 合集")
            }

            progress?.let { p ->
                Spacer(Modifier.height(6.dp))
                if (p.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (p.text.isNotBlank()) {
                    Text(
                        text = p.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                p.error?.let { err ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = onConsumeProgressError) { Text("知道了") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(playlists, key = { it.id }) { playlist ->
                    val selected = playlist.id == selectedId
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        onClick = { onSelect(playlist.id) },
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                text = playlist.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sourceLabel(playlist.sourceType),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    placeholder = { Text("歌单名称") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newTitle.isNotBlank()) onCreate(newTitle.trim())
                        newTitle = ""
                        showCreate = false
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    if (showFavPicker) {
        AlertDialog(
            onDismissRequest = {
                showFavPicker = false
                onDismissFavPicker()
            },
            title = { Text("选择要导入的收藏夹") },
            text = {
                if (favFolders.isEmpty()) {
                    Text("没有读取到收藏夹（需要先登录，或在收藏夹里至少有一个）")
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(favFolders, key = { it.id }) { folder ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onImportFolder(folder.id, folder.title.ifBlank { "收藏夹" })
                                    showFavPicker = false
                                    onDismissFavPicker()
                                },
                            ) {
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        text = folder.title.ifBlank { "未命名收藏夹" },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = "${folder.mediaCount} 首",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFavPicker = false; onDismissFavPicker() }) { Text("取消") }
            },
        )
    }

    // UP主投稿 / 合集导入：粘贴主页或合集链接，也可以直接填数字 ID
    if (showSeason) {
        AlertDialog(
            onDismissRequest = { showSeason = false },
            title = { Text("导入 UP主投稿 / 合集") },
            text = {
                Column {
                    Text(
                        text = "· UP主主页：space.bilibili.com/1234567（或直接填 UID）\n" +
                            "· 合集：.../channel/collectiondetail?sid=12345（或直接填合集 ID）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = seasonInput,
                        onValueChange = { seasonInput = it },
                        singleLine = true,
                        placeholder = { Text("主页链接 / UID / 合集链接 / 合集 ID") },
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            onImportSpace(seasonInput)
                            seasonInput = ""
                            showSeason = false
                        },
                        enabled = seasonInput.isNotBlank(),
                    ) { Text("导入 UP主投稿") }
                    TextButton(
                        onClick = {
                            onImportSeason(seasonInput)
                            seasonInput = ""
                            showSeason = false
                        },
                        enabled = seasonInput.isNotBlank(),
                    ) { Text("导入合集") }
                }
            },
            dismissButton = { TextButton(onClick = { showSeason = false }) { Text("取消") } },
        )
    }
}

private fun sourceLabel(sourceType: String): String = when (sourceType) {
    "FAV" -> "收藏夹"
    "TOVIEW" -> "稍后再看"
    "UPLOAD" -> "UP主投稿"
    "SEASON" -> "合集"
    "LOCAL" -> "本地音乐"
    else -> "手动新建"
}

/** 歌单详情：曲目列表 + 全部播放 */
@Composable
fun PlaylistDetail(
    playlistTitle: String,
    songs: List<StoredSong>,
    currentIndex: Int,
    playlists: List<StoredPlaylist>,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onCopyTo: (Long, Set<Long>) -> Unit,
    onMoveTo: (Long, Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // null=没开菜单；"copy"/"move"=正在挑目标歌单
    var menuFor by remember { mutableStateOf<String?>(null) }
    val targets = playlists.filter { it.title != playlistTitle }
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (playlistTitle.isBlank()) "请选择左侧的歌单" else "$playlistTitle（${songs.size} 首）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (songs.isNotEmpty()) {
                    Button(onClick = onPlayAll) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("播放全部")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            selectionMode = !selectionMode
                            selected = emptySet()
                        },
                    ) { Text(if (selectionMode) "退出多选" else "多选") }
                }
            }

            // 多选操作栏：全选/反选 + 复制到/移动到另一个歌单
            if (selectionMode && songs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 首", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { selected = songs.map { it.id }.toSet() }) { Text("全选") }
                    TextButton(
                        onClick = {
                            val all = songs.map { it.id }.toSet()
                            selected = all - selected
                        },
                    ) { Text("反选") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { menuFor = "copy" },
                        enabled = selected.isNotEmpty() && targets.isNotEmpty(),
                    ) { Text("复制到") }
                    TextButton(
                        onClick = { menuFor = "move" },
                        enabled = selected.isNotEmpty() && targets.isNotEmpty(),
                    ) { Text("移动到") }
                }
                if (targets.isEmpty()) {
                    Text(
                        text = "需要至少两个歌单才能复制/移动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 目标歌单选择（下拉，等价于安卓端那个「复制到哪个歌单」弹窗）
            DropdownMenu(expanded = menuFor != null, onDismissRequest = { menuFor = null }) {
                targets.forEach { target ->
                    DropdownMenuItem(
                        text = { Text("${target.title}（${target.sourceType}）") },
                        onClick = {
                            val ids = selected
                            if (menuFor == "copy") onCopyTo(target.id, ids) else onMoveTo(target.id, ids)
                            menuFor = null
                            selectionMode = false
                            selected = emptySet()
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (songs.isEmpty()) {
                Text(
                    text = "这个歌单还没有曲目。可以用上方的「从收藏夹导入」或「导入稍后再看」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(songs, key = { it.id }) { song ->
                        val index = songs.indexOf(song)
                        Surface(
                            color = if (selected.contains(song.id)) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else if (index == currentIndex) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            onClick = {
                                if (selectionMode) {
                                    selected = if (selected.contains(song.id)) {
                                        selected - song.id
                                    } else {
                                        selected + song.id
                                    }
                                } else {
                                    onPlayAt(index)
                                }
                            },
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    text = "${index + 1}. ${song.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = song.artist.ifBlank { "未知UP主" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
