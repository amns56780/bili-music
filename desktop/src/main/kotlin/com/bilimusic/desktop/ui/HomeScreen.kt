package com.bilimusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.bilimusic.desktop.auth.AuthUiState
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.player.PlaybackUiState

/**
 * 登录后的主界面（Phase 3 版本）：
 * 顶部账号栏 + 「粘贴 BV 号/链接直接播放」+ 播放器面板 + 播放队列。
 * 歌单相关界面在 Phase 4 接入。
 */
@Composable
fun HomeScreen(
    auth: AuthUiState,
    playback: PlaybackUiState,
    cover: ImageBitmap?,
    controller: PlaybackController,
    onOpenInput: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        // 顶部账号栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("bilimusic", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(16.dp))
            Text(
                text = auth.account?.let { "${it.name} · ${it.uidText}" } ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLogout) {
                Icon(Icons.Outlined.Logout, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("退出登录")
            }
        }

        Spacer(Modifier.height(16.dp))

        // 直接播放入口
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("粘贴 BV 号 / av 号 / 视频链接 / b23.tv 短链") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    hint = null
                    onOpenInput(input)
                },
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.Outlined.PlayCircle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("播放")
            }
        }

        (playback.message ?: hint)?.let { message ->
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = controller::consumeMessage) { Text("知道了") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                PlayerPanel(state = playback, cover = cover, controller = controller)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "歌单导入 / 本地音乐 / 定时关闭 / 托盘与全局快捷键会在后续阶段接入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Box(modifier = Modifier.width(340.dp)) {
                QueueList(state = playback, onPick = controller::playAt)
            }
        }
    }
}
