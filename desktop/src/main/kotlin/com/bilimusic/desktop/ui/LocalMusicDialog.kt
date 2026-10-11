package com.bilimusic.desktop.ui

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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilimusic.desktop.data.LocalAudioFile
import com.bilimusic.desktop.data.LocalMusicUiState

/**
 * 本地音乐对话框：扫描电脑里的音频 → 直接播放 / 导入到「本地音乐」歌单。
 */
@Composable
fun LocalMusicDialog(
    state: LocalMusicUiState,
    scanDirs: List<String>,
    custom: Boolean,
    onScan: () -> Unit,
    onAddDir: () -> Unit,
    onRemoveDir: (String) -> Unit,
    onResetDirs: () -> Unit,
    onPlayAll: (List<LocalAudioFile>) -> Unit,
    onImport: (List<LocalAudioFile>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地音乐（${state.files.size}）") },
        text = {
            Column(modifier = Modifier.width(620.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (custom) "扫描目录（自定义）：" else "扫描目录（默认）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onAddDir) { Text("+ 添加文件夹") }
                    if (custom) {
                        TextButton(onClick = onResetDirs) { Text("恢复默认") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onScan) { Text("重新扫描") }
                }
                // 每个扫描目录一行，右侧 × 可移除
                scanDirs.forEach { dir ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.height(14.dp).width(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = dir,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemoveDir(dir) }) { Text("×") }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (state.scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("正在扫描…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                state.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (state.files.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(state.files, key = { it.path }) { file ->
                            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Text(
                                        text = file.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = file.folder.ifBlank { "本地文件" },
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
        },
        confirmButton = {
            Row {
                OutlinedButton(onClick = onScan, enabled = !state.scanning) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.files.isEmpty()) "开始扫描" else "重新扫描")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onPlayAll(state.files) },
                    enabled = state.files.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("全部播放")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onImport(state.files) },
                    enabled = state.files.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.PlaylistAdd, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("导入到歌单")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
