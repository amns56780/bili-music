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
import com.bilimusic.desktop.data.DownloadService
import com.bilimusic.desktop.data.ImportService
import com.bilimusic.desktop.data.LocalMusicController
import com.bilimusic.desktop.data.LocalMusicScanner
import com.bilimusic.desktop.data.PlaylistStore
import com.bilimusic.desktop.media.MediaOpener
import com.bilimusic.desktop.player.GlobalHotkeys
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.player.SleepTimer
import com.bilimusic.desktop.player.Track
import com.bilimusic.desktop.ui.HomeScreen
import com.bilimusic.desktop.ui.LoginScreen
import androidx.compose.foundation.layout.fillMaxWidth
import com.bilimusic.desktop.ui.AppTitleBar
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
    // 与安卓端同一个 BM 图标（打包在 resources/icons 里）
    val appIcon = rememberAppIcon()

    // 应用级依赖建在 application 作用域，托盘菜单才能直接控制播放
    val app = rememberDesktopApp(scope)

    Window(
        // 关闭窗口只收进托盘，音乐继续放（托盘菜单里可真正退出）
        onCloseRequest = { windowVisible = false },
        state = windowState,
        title = "bilimusic",
        icon = appIcon,
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
        // 保留系统标题栏：拖动交给 DWM 处理才够顺滑（这也是 DeepSeek Harness 的做法）。
        // 这里只把标题栏改成深色，让它跟应用的深色界面协调，不再自己画标题栏。
        com.bilimusic.desktop.ui.applyWindowsDarkTitleBar(window, "bilimusic")
        BiliMusicDesktopApp(app)
    }

    // 托盘改用原生 AWT 实现：Compose 自带 Tray 的右键菜单是原生菜单，
    // 中文字形不受 Compose/Skia 字体控制，UIManager 也管不到，实测会乱码。
    // 见 NativeTray：逐项 MenuItem.setFont() 才能真正修好。
    val nativeTray = remember {
        val iconPng = object {}.javaClass.getResourceAsStream("/icons/ic_launcher.png")
            ?.use { it.readBytes() }
            ?: ByteArray(0)
        com.bilimusic.desktop.ui.NativeTray(
            iconPng = iconPng,
            tooltip = "bilimusic",
            onShowWindow = { windowVisible = true },
            onPlayPause = { app.playback?.togglePlayPause() },
            onPrevious = { app.playback?.previous() },
            onNext = { app.playback?.next() },
            onExit = ::exitApplication,
        )
    }
    // install() 是幂等的，重复调用没有副作用
    nativeTray.install()
}

/** 创建应用级依赖（只建一次） */
@Composable
private fun rememberDesktopApp(scope: kotlinx.coroutines.CoroutineScope): DesktopApp = remember {
    // The tray right-click menu is rendered by AWT (not Compose/Skia), so it needs an
    // explicit CJK-capable font - otherwise the menu labels come out as garbage.
    // Runs before Tray(...) is created.
    runCatching {
        System.setProperty("awt.useSystemAAFontSettings", "on")
        System.setProperty("swing.aatext", "true")
        val uiFont = java.awt.Font("Microsoft YaHei UI", java.awt.Font.PLAIN, 12)
        listOf("MenuItem.font", "Menu.font", "PopupMenu.font", "Label.font", "CheckBoxMenuItem.font")
            .forEach { javax.swing.UIManager.put(it, uiFont) }
    }
    val storage = DesktopStorage()
    val client = BiliClient.create(storage)
    val auth = AuthController(client = client, scope = scope)
    // VLC 缺失时不要让整个应用崩掉，降级为提示
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
    val downloads = DownloadService(client.okHttpClient, streamUrls, scope)
    // VLC 缺失时不要让整个应用崩掉，降级为提示
    val playback = runCatching {
        PlaybackController(
        settings = storage,
            client = client,
            streamUrls = streamUrls,
            scope = scope,
            // 已下载的曲子离线播放，不再取流
            downloadedFile = { bvid, cid -> downloads.localFileFor(bvid, cid)?.absolutePath },
        )
    }.getOrNull()
    val store = PlaylistStore()
    // 系统级全局媒体键：注册到 Win32（被占用时只记日志，不影响使用）
    val hotkeys = playback?.let { player ->
        runCatching {
            GlobalHotkeys(
                onPlayPause = { player.togglePlayPause() },
                onNext = { player.next() },
                onPrevious = { player.previous() },
            ).also { it.start() }
        }.getOrNull()
    }
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
        downloads = downloads,
        playback = playback,
        opener = playback?.let { MediaOpener(client, it, scope) },
        sleepTimer = sleepTimer,
        hotkeys = hotkeys,
    )
}

/** 应用图标：直接用安卓端那套 BM 图标（打包在资源和 jar 里） */
@Composable
private fun rememberAppIcon(): Painter = remember {
    val bytes = object {}.javaClass.getResourceAsStream("/icons/ic_launcher.png")?.use { it.readBytes() }
    if (bytes != null) {
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
    } else {
        // 兜底：万一资源缺失，画一个纯色圆，保证不崩
        val size = 64
        val bytes2 = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = (x - 32) * (x - 32) + (y - 32) * (y - 32) <= 30 * 30
                val o = (y * size + x) * 4
                if (inside) {
                    bytes2[o] = 0xA4.toByte(); bytes2[o + 1] = 0x50.toByte()
                    bytes2[o + 2] = 0x67.toByte(); bytes2[o + 3] = 0xFF.toByte()
                }
            }
        }
        val bmp = org.jetbrains.skia.Bitmap().apply {
            allocN32Pixels(size, size, false)
            installPixels(
                org.jetbrains.skia.ImageInfo.makeN32(
                    size,
                    size,
                    org.jetbrains.skia.ColorAlphaType.PREMUL,
                ),
                bytes2,
                size * 4,
            )
        }
        BitmapPainter(org.jetbrains.skia.Image.makeFromBitmap(bmp).toComposeImageBitmap())
    }
}

/** 应用级依赖（只建一次） */
private class DesktopApp(
    val storage: DesktopStorage,
    val client: BiliClient,
    val auth: AuthController,
    val store: PlaylistStore,
    val importer: ImportService,
    val localMusic: LocalMusicController,
    val downloads: DownloadService,
    /** VLC 不可用时为 null（例如没装 VLC），此时只提示不崩溃 */
    val playback: PlaybackController?,
    val opener: MediaOpener?,
    val sleepTimer: SleepTimer,
    /** 系统级全局媒体键（注册失败时为 null，不影响其它功能） */
    val hotkeys: GlobalHotkeys?,
)

@Composable
private fun BiliMusicDesktopApp(app: DesktopApp) {
    LaunchedEffect(app) { app.auth.bootstrap() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val authState by app.auth.state.collectAsState()
            // 本地优先：不强制登录。默认直接进主界面（本地音乐随便听），
            // 只有要点网络歌单/在线播放时才引导登录。
            var showLogin by remember { mutableStateOf(false) }
            LaunchedEffect(authState.phase) {
                if (authState.phase == AuthUiState.Phase.LoggedIn) showLogin = false
            }
            when {
                authState.phase == AuthUiState.Phase.Checking -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                showLogin -> LoginScreen(
                    state = authState,
                    onStartQr = app.auth::startQrLogin,
                    onSubmitCookie = app.auth::submitCookie,
                    onConsumeError = app.auth::consumeError,
                    onBack = { showLogin = false },
                )

                else -> {
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
                        val downloadState by app.downloads.state.collectAsState()

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
                            localScanDirs = localMusicState.dirs,
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
                            onImportToView = {
                                if (authState.account == null) {
                                    showLogin = true
                                    playback.reportError("导入网络歌单需要先登录 B 站账号")
                                } else {
                                    app.importer.importToView()
                                }
                            },
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
                            onAddLocalDir = {
                                // 目录选择器（Swing JFileChooser 支持纯目录模式，可多选）
                                val chooser = javax.swing.JFileChooser().apply {
                                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "选择要扫描的音乐文件夹"
                                    isMultiSelectionEnabled = true
                                }
                                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    var added = 0
                                    chooser.selectedFiles.forEach { dir ->
                                        if (app.localMusic.addDir(dir.absolutePath)) added++
                                    }
                                    playback.reportError(
                                        if (added > 0) "已添加 $added 个扫描目录，正在重新扫描…"
                                        else "这些文件夹已经在扫描列表里了",
                                    )
                                }
                            },
                            onRemoveLocalDir = { dir ->
                                app.localMusic.removeDir(dir)
                                playback.reportError("已移除扫描目录：$dir")
                            },
                            onResetLocalDirs = {
                                app.localMusic.resetDirs()
                                playback.reportError("已恢复默认扫描目录（音乐 / 下载 / 桌面）")
                            },
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
                            downloadBusy = downloadState.busy,
                            onDownload = {
                                playbackState.current?.let { track -> app.downloads.download(track) }
                            },
                            extraMessage = downloadState.message,
                            onCopyTo = { targetId, ids ->
                                val added = app.store.copySongsTo(targetId, ids)
                                playback.reportError("已复制 $added 首（重复的自动跳过）")
                            },
                            onMoveTo = { targetId, ids ->
                                val added = app.store.moveSongsTo(targetId, ids)
                                playback.reportError("已移动 $added 首（重复的自动跳过）")
                            },
                            onImportSeason = { raw ->
                                if (authState.account == null) {
                                    showLogin = true
                                    playback.reportError("导入网络歌单需要先登录 B 站账号")
                                } else {
                                    // 从合集链接里取第一个长数字（season_id / sid），或直接填 ID
                                    val id = Regex("""\d{3,}""").find(raw)?.value?.toLongOrNull()
                                    if (id != null) {
                                        app.importer.importSeason(id, "合集 $id")
                                    } else {
                                        playback.reportError("没认出合集 ID：请粘贴完整合集链接，或直接填数字 ID")
                                    }
                                }
                            },
                            onImportSpace = { raw ->
                                if (authState.account == null) {
                                    showLogin = true
                                    playback.reportError("导入网络歌单需要先登录 B 站账号")
                                } else {
                                    // UP主主页链接里的数字就是 mid
                                    val mid = Regex("""\d{3,}""").find(raw)?.value?.toLongOrNull()
                                    if (mid != null) {
                                        app.importer.importSpaceUploads(mid)
                                    } else {
                                        playback.reportError("没认出 UP主 UID：请粘贴主页链接，或直接填数字 UID")
                                    }
                                }
                            },
                            onRequestLogin = { showLogin = true },
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
