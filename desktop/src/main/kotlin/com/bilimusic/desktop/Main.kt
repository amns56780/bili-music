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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.bilimusic.app.data.player.AudioQualityProvider
import com.bilimusic.app.data.player.StreamUrlRepository
import com.bilimusic.app.data.remote.BiliClient
import com.bilimusic.app.data.remote.dto.FavFolderDto
import com.bilimusic.app.domain.model.AudioQualityOption
import com.bilimusic.desktop.auth.AuthController
import com.bilimusic.desktop.auth.AuthUiState
import com.bilimusic.desktop.data.DesktopStorage
import com.bilimusic.desktop.data.ImportService
import com.bilimusic.desktop.data.LocalMusicController
import com.bilimusic.desktop.data.LocalMusicScanner
import com.bilimusic.desktop.data.PlaylistStore
import com.bilimusic.desktop.media.MediaOpener
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.player.SleepTimer
import com.bilimusic.desktop.player.Track
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
    var windowVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val trayState = rememberTrayState()

    // 应用级依赖建在 application 作用域，托盘菜单才能直接控制播放
    val app = rememberDesktopApp(scope)

    Window(
        // 关闭窗口只收进托盘，音乐继续放（托盘菜单里可真正退出）
        onCloseRequest = { windowVisible = false },
        state = windowState,
        title = "bilimusic",
        visible = windowVisible,
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                when (event.key) {
                    Key.Spacebar -> {
                        app.playback?.togglePlayPause()
                        true
                    }

                    Key.MediaPlayPause -> {
                        app.playback?.togglePlayPause()
                        true
                    }

                    else -> false
                }
            } else {
                false
            }
        },
    ) {
        BiliMusicDesktopApp(app)
    }

    Tray(
        icon = rememberBilimusicTrayIcon(),
        state = trayState,
        tooltip = "bilimusic",
        onAction = { windowVisible = true },
        menu = {
            Item("显示窗口", onClick = { windowVisible = true })
            Separator()
            Item("播放 / 暂停", onClick = { app.playback?.togglePlayPause() })
            Item("上一首", onClick = { app.playback?.previous() })
            Item("下一首", onClick = { app.playback?.next() })
            Separator()
            Item("退出", onClick = ::exitApplication)
        },
    )
}

/** 创建应用级依赖（只建一次） */
@Composable
private fun rememberDesktopApp(scope: kotlinx.coroutines.CoroutineScope): DesktopApp = remember {
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
    val store = PlaylistStore()
    val sleepTimer = SleepTimer(scope) { stopAfterCurrent ->
        if (stopAfterCurrent) {
            // 等当前这首播完再停（由播放器的曲末回调执行）
            playback?.requestStopAfterCurrent()
        } else {
            playback?.stopNow()
        }
    }
    DesktopApp(
        storage = storage,
        client = client,
        auth = auth,
        store = store,
        importer = ImportService(client, store, scope),
        localMusic = LocalMusicController(LocalMusicScanner(), store, scope),
        playback = playback,
        opener = playback?.let { MediaOpener(client, it, scope) },
        sleepTimer = sleepTimer,
    )
}

/** 托盘图标：用 Skia 画一个圆角方块 + 音符，不依赖额外资源文件 */
@Composable
private fun rememberBilimusicTrayIcon(): Painter = remember {
    val size = 64
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val inCircle = (x - 32) * (x - 32) + (y - 32) * (y - 32) <= 30 * 30
            pixels[y * size + x] = if (inCircle) 0xFF6750A4.toInt() else 0x00000000
        }
    }
    val bytes = ByteArray(size * size * 4)
    pixels.forEachIndexed { i, argb ->
        val o = i * 4
        bytes[o] = (argb and 0xFF).toByte()
        bytes[o + 1] = ((argb shr 8) and 0xFF).toByte()
        bytes[o + 2] = ((argb shr 16) and 0xFF).toByte()
        bytes[o + 3] = ((argb shr 24) and 0xFF).toByte()
    }
    val bitmap = org.jetbrains.skia.Bitmap().apply {
        allocN32Pixels(size, size, false)
        installPixels(
            org.jetbrains.skia.ImageInfo.makeN32(
                size,
                size,
                org.jetbrains.skia.ColorAlphaType.PREMUL,
            ),
            bytes,
            size * 4,
        )
    }
    BitmapPainter(org.jetbrains.skia.Image.makeFromBitmap(bitmap).toComposeImageBitmap())
}

/** 应用级依赖（只建一次） */
private class DesktopApp(
    val storage: DesktopStorage,
    val client: BiliClient,
    val auth: AuthController,
    val store: PlaylistStore,
    val importer: ImportService,
    val localMusic: LocalMusicController,
    /** VLC 不可用时为 null（例如没装 VLC），此时只提示不崩溃 */
    val playback: PlaybackController?,
    val opener: MediaOpener?,
    val sleepTimer: SleepTimer,
)

@Composable
private fun BiliMusicDesktopApp(app: DesktopApp) {
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
                        // 歌单状态
                        val playlists by app.store.playlists.collectAsState()
                        val allSongs by app.store.songs.collectAsState()
                        val importProgress by app.importer.progress.collectAsState()
                        var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
                        var favFolders by remember { mutableStateOf<List<FavFolderDto>>(emptyList()) }
                        var showLocalMusic by remember { mutableStateOf(false) }
                        val localMusicState by app.localMusic.state.collectAsState()
                        val sleepState by app.sleepTimer.state.collectAsState()

                        val selected = playlists.firstOrNull { it.id == selectedPlaylistId }
                        val songs = allSongs
                            .filter { it.playlistId == selectedPlaylistId }
                            .sortedBy { it.sortOrder }

                        HomeScreen(
                            auth = authState,
                            playback = playbackState,
                            cover = cover,
                            controller = playback,
                            playlists = playlists,
                            selectedPlaylistId = selectedPlaylistId,
                            selectedPlaylistTitle = selected?.title.orEmpty(),
                            songs = songs,
                            importProgress = importProgress.takeIf { it.running || it.text.isNotBlank() || it.error != null },
                            favFolders = favFolders,
                            showLocalMusic = showLocalMusic,
                            localMusic = localMusicState,
                            localScanDirs = remember { app.localMusic.scanDirs() },
                            onOpenInput = { text -> app.opener?.open(text) },
                            onLogout = app.auth::logout,
                            onSelectPlaylist = { selectedPlaylistId = it },
                            onCreatePlaylist = { title ->
                                selectedPlaylistId = app.store.createPlaylist(title)
                            },
                            onLoadFavFolders = {
                                authState.account?.let { account ->
                                    app.importer.loadFavFolders(account.mid) { favFolders = it }
                                }
                            },
                            onImportFolder = { id, title -> app.importer.importFavFolder(id, title) },
                            onImportToView = app.importer::importToView,
                            onDismissFavPicker = { favFolders = emptyList() },
                            onConsumeProgressError = app.importer::consumeError,
                            onPlayPlaylist = { index ->
                                val tracks = songs.map { song ->
                                    Track(
                                        bvid = song.bvid,
                                        cid = song.cid,
                                        title = song.title,
                                        artist = song.artist.ifBlank { "未知UP主" },
                                        coverUrl = song.coverUrl,
                                        durationMs = song.durationMs,
                                    )
                                }
                                playback.playQueue(tracks, index)
                            },
                            onOpenLocalMusic = {
                                showLocalMusic = true
                                app.localMusic.scan()
                            },
                            onScanLocal = app.localMusic::scan,
                            onPlayLocal = { files ->
                                playback.playQueue(
                                    files.map { file ->
                                        Track(
                                            bvid = "local",
                                            cid = file.path.hashCode().toLong(),
                                            title = file.title,
                                            artist = file.folder.ifBlank { "本地文件" },
                                            localPath = file.path,
                                        )
                                    },
                                    0,
                                )
                            },
                            onImportLocal = { files ->
                                app.localMusic.importToLocalPlaylist(files)?.let { id ->
                                    selectedPlaylistId = id
                                }
                            },
                            onDismissLocalMusic = { showLocalMusic = false },
                            sleepTimer = sleepState,
                            onStartSleep = { minutes -> app.sleepTimer.start(minutes) },
                            onCancelSleep = app.sleepTimer::cancel,
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
