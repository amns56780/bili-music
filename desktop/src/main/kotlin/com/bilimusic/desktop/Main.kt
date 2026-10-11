package com.bilimusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bilimusic.app.data.player.AudioQualityProvider
import com.bilimusic.app.data.player.StreamUrlRepository
import com.bilimusic.app.data.remote.BiliClient
import com.bilimusic.app.domain.model.AudioQualityOption
import com.bilimusic.desktop.auth.AuthController
import com.bilimusic.desktop.auth.AuthUiState
import com.bilimusic.desktop.data.DesktopStorage
import com.bilimusic.desktop.media.MediaOpener
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.ui.HomeScreen
import com.bilimusic.desktop.ui.LoginScreen
import com.bilimusic.desktop.ui.rememberRemoteImage

/**
 * bilimusic Windows 桌面版入口。
 *
 * 与安卓版共用 `shared` 模块：WBI 签名、接口 DTO/API、Cookie 解析、取流逻辑、音质选择、播放模式。
 * 桌面独有的是 UI 与 VLC 播放内核（见 [com.bilimusic.desktop.player.VlcPlayer]）。
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1240.dp, 860.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "bilimusic",
    ) {
        BiliMusicDesktopApp()
    }
}

/** 应用级依赖（只建一次） */
private class DesktopApp(
    val storage: DesktopStorage,
    val client: BiliClient,
    val auth: AuthController,
    /** VLC 不可用时为 null（例如没装 VLC），此时只提示不崩溃 */
    val playback: PlaybackController?,
    val opener: MediaOpener?,
)

@Composable
private fun BiliMusicDesktopApp() {
    val scope = rememberCoroutineScope()

    val app = remember {
        val storage = DesktopStorage()
        val client = BiliClient.create(storage)
        val auth = AuthController(client = client, scope = scope)
        // VLC 缺失时不要让整个应用崩掉，降级为提示
        val playback = runCatching {
            val qualityProvider = AudioQualityProvider {
                AudioQualityOption.fromNameOrAuto(
                    storage.getString(KEY_AUDIO_QUALITY) ?: AudioQualityOption.AUTO.name,
                )
            }
            val streamUrls = StreamUrlRepository(
                api = client.api,
                wbiSigner = client.wbiSigner,
                qualityProvider = qualityProvider,
                throttle = client.requestThrottle,
            )
            PlaybackController(client = client, streamUrls = streamUrls, scope = scope)
        }.getOrNull()
        DesktopApp(
            storage = storage,
            client = client,
            auth = auth,
            playback = playback,
            opener = playback?.let { MediaOpener(client, it, scope) },
        )
    }

    LaunchedEffect(app) { app.auth.bootstrap() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val authState by app.auth.state.collectAsState()
            when (authState.phase) {
                AuthUiState.Phase.Checking -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                AuthUiState.Phase.LoggedOut -> LoginScreen(
                    state = authState,
                    onStartQr = app.auth::startQrLogin,
                    onSubmitCookie = app.auth::submitCookie,
                    onConsumeError = app.auth::consumeError,
                )

                AuthUiState.Phase.LoggedIn -> {
                    val playback = app.playback
                    if (playback == null) {
                        VlcMissingHint()
                    } else {
                        val playbackState by playback.state.collectAsState()
                        val cover = rememberRemoteImage(
                            url = playbackState.current?.coverUrl,
                            client = app.client.okHttpClient,
                        )
                        HomeScreen(
                            auth = authState,
                            playback = playbackState,
                            cover = cover,
                            controller = playback,
                            onOpenInput = { text -> app.opener?.open(text) },
                            onLogout = app.auth::logout,
                        )
                    }
                }
            }
        }
    }
}

/** 没装 VLC 时的提示（播放内核依赖系统 VLC） */
@Composable
private fun VlcMissingHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("没有找到 VLC", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "桌面版用 VLC 作为播放内核（B 站的 m4a/aac/flac 都能播）。\n" +
                "请先安装 VLC（videolan.org），然后重新启动本程序。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val KEY_AUDIO_QUALITY = "audio_quality"
