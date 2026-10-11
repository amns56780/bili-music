package com.bilimusic.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bilimusic.desktop.auth.AuthController
import com.bilimusic.desktop.auth.AuthUiState

/**
 * 登录页（FR-1）：扫码登录 + 粘贴 Cookie 两条路径，跟安卓端一致。
 */
@Composable
fun LoginScreen(
    state: AuthUiState,
    onStartQr: () -> Unit,
    onSubmitCookie: (String) -> Unit,
    onConsumeError: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var cookieText by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("bilimusic", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "B站音源桌面播放器 · 登录后即可导入歌单并取流播放",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            TabRow(selectedTabIndex = tab, modifier = Modifier.width(520.dp)) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("扫码登录") },
                    icon = { Icon(Icons.Outlined.QrCode2, contentDescription = null) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("粘贴 Cookie") },
                    icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                )
            }

            Spacer(Modifier.height(28.dp))

            Box(modifier = Modifier.width(560.dp), contentAlignment = Alignment.Center) {
                when (tab) {
                    0 -> QrLoginPane(state = state, onStartQr = onStartQr)
                    else -> CookieLoginPane(
                        text = cookieText,
                        onTextChange = { cookieText = it },
                        busy = state.busy,
                        onSubmit = { onSubmitCookie(cookieText) },
                    )
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onConsumeError) { Text("知道了") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrLoginPane(state: AuthUiState, onStartQr: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val bitmap = remember(state.qrContent) {
            state.qrContent?.let { encodeQrToImageBitmap(it, 320) }
        }

        Surface(
            color = Color.White,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(340.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    bitmap != null -> Image(bitmap = bitmap, contentDescription = "登录二维码")
                    state.busy -> CircularProgressIndicator()
                    else -> Text(
                        text = "点击下方按钮获取二维码",
                        color = Color(0xFF666666),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = state.qrStatus.ifBlank { "扫码后请在手机上确认登录" },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.qrContent != null && state.qrRemainingSec > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "二维码有效期剩余 ${state.qrRemainingSec} 秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStartQr, enabled = !state.busy) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.qrContent == null) "获取二维码" else "刷新二维码")
        }
    }
}

@Composable
private fun CookieLoginPane(
    text: String,
    onTextChange: (String) -> Unit,
    busy: Boolean,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "从浏览器开发者工具里复制整段 Cookie（或只复制 SESSDATA 的值）粘贴到下面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().height(160.dp),
            placeholder = { Text("SESSDATA=xxx; bili_jct=yyy; DedeUserID=123") },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSubmit, enabled = !busy && text.isNotBlank()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("登录")
            }
        }
    }
}
