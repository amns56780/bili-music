package com.bilimusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bilimusic.app.data.remote.BiliClient
import com.bilimusic.desktop.auth.AuthController
import com.bilimusic.desktop.auth.AuthUiState
import com.bilimusic.desktop.data.DesktopStorage
import com.bilimusic.desktop.ui.LoginScreen

/**
 * bilimusic Windows 桌面版入口。
 *
 * 与安卓版共用 `shared` 模块：WBI 签名、接口 DTO/API、Cookie 解析、音质选择、链接解析都在那边。
 * 这里负责桌面 UI 与（后续阶段的）VLC 播放内核。
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1180.dp, 820.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "bilimusic",
    ) {
        BiliMusicDesktopApp()
    }
}

@Composable
private fun BiliMusicDesktopApp() {
    val scope = rememberCoroutineScope()
    // 组合根：本地存储 → 网络客户端 → 登录状态机
    val controller = remember {
        val storage = DesktopStorage()
        AuthController(client = BiliClient.create(storage), scope = scope)
    }

    LaunchedEffect(controller) { controller.bootstrap() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val state by controller.state.collectAsState()
            when (state.phase) {
                AuthUiState.Phase.Checking -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                AuthUiState.Phase.LoggedOut -> LoginScreen(
                    state = state,
                    onStartQr = controller::startQrLogin,
                    onSubmitCookie = controller::submitCookie,
                    onConsumeError = controller::consumeError,
                )

                AuthUiState.Phase.LoggedIn -> HomePlaceholder(
                    state = state,
                    onLogout = controller::logout,
                )
            }
        }
    }
}

/** 登录后的首页占位：歌单 / 播放等界面在后续阶段接入 */
@Composable
private fun HomePlaceholder(state: AuthUiState, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("已登录", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.account?.let { "${it.name}（${it.uidText}）" } ?: "账号信息读取中…",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "歌单、播放、本地音乐、托盘与下载会在后续阶段接入",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLogout) { Text("退出登录") }
    }
}
