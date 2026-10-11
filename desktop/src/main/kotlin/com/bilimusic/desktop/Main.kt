package com.bilimusic.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * bilimusic Windows 桌面版入口。
 *
 * 与安卓版同一套后端逻辑（`shared` 模块）：WBI 签名、接口 DTO/API、音质选择、链接解析都在那边，
 * 这里只负责桌面 UI 与播放内核（VLC）。
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1120.dp, 780.dp))
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
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "bilimusic 桌面版 · 正在搭建",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}
