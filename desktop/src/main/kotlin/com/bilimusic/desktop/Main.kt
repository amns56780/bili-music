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
import androidx.compose.ui.window.WindowPosition
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
import com.bilimusic.desktop.ui.AppTitleBar
import com.bilimusic.desktop.ui.HomeScreen
import com.bilimusic.desktop.ui.LoginScreen
import com.bilimusic.desktop.ui.theme.BmTheme
import androidx.compose.foundation.layout.fillMaxWidth
import com.bilimusic.desktop.ui.rememberRemoteImage

/**
 * bilimusic Windows 桌面版入口。
 *
 * 与安卓版共用 `shared` 模块：WBI 签名、接口 DTO/API、Cookie 解析、取流逻辑、音质选择、播放模式。
 * 桌面独有的是 UI 与 VLC 播放内核（见 [com.bilimusic.desktop.player.VlcPlayer]）。
 */
fun main() {
    // ★ 必须在任何 JNA/VLC 调用之前设置。
    // VlcJ 通过 JNA 把字符串传给 libvlc，JNA 默认用平台编码（中文系统是 GBK），
    // 而 libvlc 只认 UTF-8 —— 于是「中文文件名」的本地音乐全部播放失败
    // （纯英文文件名的却能播，表现极具迷惑性）。
    // JNA 在 Native 类初始化时读取这个属性，所以必须在创建 VlcPlayer 之前设好。
    System.setProperty("jna.encoding", "UTF-8")
    // 便携版内置了 VLC（vlc/ 目录）：优先用它，找不到则回退系统安装的 VLC。
    // 必须在任何 JNA/VLC 调用之前设置 jna.library.path —— JNA 的 Native 类
    // 在初始化时就读取该属性，之后再设无效。
    val builtinVlcDir = runCatching {
        val appDir = java.io.File(System.getProperty("user.dir"))
        val builtinVlc = java.io.File(appDir, "vlc")
        if (builtinVlc.isDirectory && java.io.File(builtinVlc, "libvlc.dll").isFile) {
            System.setProperty("jna.library.path", builtinVlc.absolutePath)
            builtinVlc.absolutePath
        } else {
            null
        }
    }.getOrNull()
    // 安装版是 javaw（没有控制台），stdout 等于黑洞 —— 所有日志同时写进
    // ~/.bilimusic/app.log，否则「突然没声音了」这类反馈完全没有现场可查。
    setupFileLogging()
    if (builtinVlcDir != null) {
        com.bilimusic.app.util.AppLog.i("Main", "使用内置 VLC：" + builtinVlcDir)
    }
    // 单实例保护：已有实例在跑时，通知它把窗口显示出来，然后本进程直接退出。
    // 否则会出现多个托盘图标 + 多份播放（Windows 强杀进程还会留下残留图标）。
    if (!SingleInstance.acquire()) return
    application {
    var windowVisible by remember { mutableStateOf(true) }
    // 沉浸模式全屏：记住进入前的窗口几何，退出全屏时还原
    var isFullscreen by remember { mutableStateOf(false) }
    var savedPlacement by remember { mutableStateOf<androidx.compose.ui.window.WindowPlacement?>(null) }
    val scope = rememberCoroutineScope()
    val trayState = rememberTrayState()
    // 与安卓端同一个 BM 图标（打包在 resources/icons 里）
    val appIcon = rememberAppIcon()

    // 应用级依赖建在 application 作用域，托盘菜单才能直接控制播放
    val app = rememberDesktopApp(scope)

    // 窗口几何：首次启动居中，之后继承上一次关闭时的位置与大小
    // （存 "x,y,w,h" 四个整数；坐标是 dp）
    val windowState = rememberWindowState(
        position = run {
            val parts = parseWindowGeometry(app.storage.getString(KEY_WINDOW_GEOMETRY))
            if (parts != null) WindowPosition(parts[0].dp, parts[1].dp) else WindowPosition(Alignment.Center)
        },
        size = run {
            val parts = parseWindowGeometry(app.storage.getString(KEY_WINDOW_GEOMETRY))
            if (parts != null) DpSize(parts[2].dp, parts[3].dp) else DpSize(1240.dp, 860.dp)
        },
    )

    // 播放时阻止电脑自动熄屏 / 睡眠（暂停或停止后自动恢复）。
    // 用协程直接 collect 而不是 collectAsState + LaunchedEffect(key)：
    // application 顶层不在 Window 的 composition 里，key 变化不一定触发重跑。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        app.playback?.state?.collect { s ->
            if (s.playing) {
                com.bilimusic.desktop.player.KeepAwake.enable()
            } else {
                com.bilimusic.desktop.player.KeepAwake.disable()
            }
        }
    }

    // 位置/大小变化 500ms 后落盘（LaunchedEffect 的 key 变化会取消上一次，天然防抖）
    androidx.compose.runtime.LaunchedEffect(windowState.position, windowState.size, windowVisible, isFullscreen) {
        // 全屏时不保存几何：否则全屏尺寸（屏幕大小）会被记成"上次的窗口大小"，
        // 下次启动窗口直接铺满屏幕
        if (!windowVisible || isFullscreen) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        val x = windowState.position.x.value.toInt()
        val y = windowState.position.y.value.toInt()
        val w = windowState.size.width.value.toInt()
        val h = windowState.size.height.value.toInt()
        // 最小化时 Windows 会把窗口位置挪到 -32000 附近，这种值不能保存
        if (x > -30000 && y > -30000 && w > 300 && h > 300) {
            app.storage.putString(KEY_WINDOW_GEOMETRY, "$x,$y,$w,$h")
        }
    }

    // 第二个实例启动时会把 "show" 发过来，这里响应成「把窗口显示出来」
    androidx.compose.runtime.LaunchedEffect(Unit) {
        SingleInstance.onActivate.set { windowVisible = true }
    }

    Window(
        // 关闭窗口只收进托盘，音乐继续放（托盘菜单里可真正退出）
        onCloseRequest = { windowVisible = false },
        state = windowState,
        title = "bilimusic",
        icon = appIcon,
        visible = windowVisible,
        // 无边框窗口：标题栏自己画（见 AppTitleBar），拖动由事件位移搬窗口
        undecorated = true,
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                // 注意：Compose 的 event.key.keyCode 对字符键带标志位（实测空格 = 2^32 + 32），
                // 而 Key.Spacebar.keyCode 同样带标志位，直接比是对的；
                // 只有拿 AWT 虚拟键码（方括号）比较时才要取低 32 位。
                val rawVk = event.key.keyCode
                when {
                    rawVk == Key.Spacebar.keyCode -> {
                        app.playback?.togglePlayPause()
                        true
                    }

                    rawVk == Key.MediaPlayPause.keyCode -> {
                        app.playback?.togglePlayPause()
                        true
                    }

                    else -> {
                        // 应用内快捷键：[ 上一首 / ] 下一首（窗口有焦点时生效）
                        when (rawVk and 0xFFFFFFFFL) {
                            java.awt.event.KeyEvent.VK_OPEN_BRACKET.toLong() -> {
                                app.playback?.previous()
                                true
                            }

                            java.awt.event.KeyEvent.VK_CLOSE_BRACKET.toLong() -> {
                                app.playback?.next()
                                true
                            }

                            else -> false
                        }
                    }
                }
            } else {
                false
            }
        },
    ) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(1000, 640)
            // 无边框窗口最大化时会盖住任务栏，用 maximizedBounds 收住
            runCatching {
                val toolkit = java.awt.Toolkit.getDefaultToolkit()
                val device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                val insets = toolkit.getScreenInsets(device.defaultConfiguration)
                val size = toolkit.screenSize
                window.maximizedBounds = java.awt.Rectangle(
                    insets.left,
                    insets.top,
                    size.width - insets.left - insets.right,
                    size.height - insets.top - insets.bottom,
                )
            }
            // 等 AWT peer 真正创建出 HWND 再接管拖动
            kotlinx.coroutines.delay(400)
            // 拿到窗口句柄：之后拖动标题栏直接用 Win32 SetWindowPos 在独立线程移动窗口，
            // 不经过 AWT setLocation，也不占 Compose 的 UI 线程
            val attached = com.bilimusic.desktop.ui.NativeWindowMover.attach("bilimusic")
            println("[BmChrome] native mover attached=" + attached)
        }
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            var isMaximized by remember { mutableStateOf(false) }
            var normalBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
            if (!isFullscreen) AppTitleBar(
                window = window,
                title = "bilimusic",
                icon = appIcon,
                maximized = isMaximized,
                onMinimize = {
                    // undecorated 窗口下 windowState.isMinimized 不一定生效，直接走 AWT
                    window.isMinimized = true
                },
                onToggleMaximize = {
                    // 无边框窗口上 extendedState/placement 都可能被忽略，直接算工作区并设 bounds 最可靠
                    if (!isMaximized) {
                        normalBounds = window.bounds
                        runCatching {
                            val toolkit = java.awt.Toolkit.getDefaultToolkit()
                            val device = java.awt.GraphicsEnvironment
                                .getLocalGraphicsEnvironment().defaultScreenDevice
                            val insets = toolkit.getScreenInsets(device.defaultConfiguration)
                            val size = toolkit.screenSize
                            window.bounds = java.awt.Rectangle(
                                insets.left,
                                insets.top,
                                size.width - insets.left - insets.right,
                                size.height - insets.top - insets.bottom,
                            )
                        }
                        isMaximized = true
                    } else {
                        normalBounds?.let { window.bounds = it }
                        isMaximized = false
                    }
                },
                onClose = { windowVisible = false },
            )
            Box(modifier = Modifier.weight(1f)) {
                BiliMusicDesktopApp(
                    app = app,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = {
                        if (isFullscreen) {
                            windowState.placement = savedPlacement
                                ?: androidx.compose.ui.window.WindowPlacement.Floating
                            isFullscreen = false
                        } else {
                            savedPlacement = windowState.placement
                            windowState.placement = androidx.compose.ui.window.WindowPlacement.Fullscreen
                            isFullscreen = true
                        }
                    },
                )
            }
        }
        }
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
            // 播放前把整首下载到流缓存，避免流式播放被网络抖动打断
            fetchToCache = { track -> downloads.fetchToCache(track) },
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
private fun BiliMusicDesktopApp(
    app: DesktopApp,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    LaunchedEffect(app) { app.auth.bootstrap() }

    DesktopTheme(app.client.okHttpClient) {
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
                        // 记住上次看的是哪个歌单；首次启动/记录失效则落在第一个歌单上
                        var selectedPlaylistId by remember {
                            mutableStateOf(
                                app.storage.getString(KEY_SELECTED_PLAYLIST)?.toLongOrNull(),
                            )
                        }
                        androidx.compose.runtime.LaunchedEffect(playlists) {
                            if (playlists.isNotEmpty() && playlists.none { it.id == selectedPlaylistId }) {
                                selectedPlaylistId = playlists.first().id
                            }
                        }
                        androidx.compose.runtime.LaunchedEffect(selectedPlaylistId) {
                            selectedPlaylistId?.let {
                                app.storage.putString(KEY_SELECTED_PLAYLIST, it.toString())
                            }
                        }
                        var favFolders by remember { mutableStateOf<List<FavFolderDto>>(emptyList()) }
                        var showLocalMusic by remember { mutableStateOf(false) }
                        // 沉浸听歌模式的状态（背景 / 律动开关）；本次会话内有效
                        val immersiveState = remember { com.bilimusic.desktop.ui.ImmersiveState() }
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
                            selectedPlaylist = selected,
                            songCounts = allSongs.groupingBy { it.playlistId }.eachCount(),
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
                            onImportShareLink = { text ->
                                app.importer.importFromShareLink(text)
                            },
                            onImportBvids = { text ->
                                val pid = selectedPlaylistId
                                if (pid == null) {
                                    playback.reportError("请先在左侧选择一个歌单，再按 BV 号导入")
                                } else {
                                    app.importer.importByBvids(
                                        text,
                                        pid,
                                        playlists.firstOrNull { it.id == pid }?.title.orEmpty(),
                                    )
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
                                        // ★ 必须带上本地路径：否则歌单里的本地曲目会被当成在线曲目去 B 站取流
                                        localPath = song.localPath,
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
                            onRemoveSongs = { ids ->
                                // 只从歌单里删记录：不动 B 站收藏，也不删本地文件
                                app.store.removeSongs(ids)
                                playback.reportError("已从歌单移除 " + ids.size + " 首")
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
                            onRenamePlaylist = { id, title ->
                                app.store.renamePlaylist(id, title)
                                playback.reportError("歌单已重命名")
                            },
                            onDeletePlaylist = { id ->
                                app.store.deletePlaylist(id)
                                playback.reportError("歌单已删除（已下载的音频文件保留）")
                            },
                            onSetCoverUrl = { id, url ->
                                app.store.setPlaylistCover(id, coverUrl = url)
                                playback.reportError(if (url == null) "已恢复默认封面" else "封面已更新")
                            },
                            onSetCoverFile = { playlistId ->
                                val chooser = javax.swing.JFileChooser().apply {
                                    dialogTitle = "选择封面图片"
                                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                        "图片 (*.png;*.jpg;*.jpeg;*.webp;*.gif)",
                                        "png", "jpg", "jpeg", "webp", "gif",
                                    )
                                }
                                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    val src = chooser.selectedFile
                                    runCatching {
                                        val dir = java.io.File(
                                            System.getProperty("user.home"),
                                            ".bilimusic/covers",
                                        ).apply { mkdirs() }
                                        val ext = src.extension.ifBlank { "png" }.lowercase()
                                        val dst = java.io.File(dir, "playlist-" + playlistId + "." + ext)
                                        java.nio.file.Files.copy(
                                            src.toPath(),
                                            dst.toPath(),
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                        )
                                        app.store.setPlaylistCover(playlistId, coverPath = dst.absolutePath)
                                        playback.reportError("封面已更新")
                                    }.onFailure {
                                        playback.reportError("设置封面失败：" + (it.message ?: "未知错误"))
                                    }
                                }
                            },
                            immersive = immersiveState,
                            onPickImmersiveImage = {
                                val chooser = javax.swing.JFileChooser().apply {
                                    dialogTitle = "选择沉浸模式背景图片"
                                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                        "图片 (*.png;*.jpg;*.jpeg;*.webp;*.bmp)",
                                        "png", "jpg", "jpeg", "webp", "bmp",
                                    )
                                }
                                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    immersiveState.customBgPath = chooser.selectedFile.absolutePath
                                    immersiveState.bgMode = com.bilimusic.desktop.ui.ImmersiveBackground.IMAGE
                                }
                            },
                            isFullscreen = isFullscreen,
                            onToggleFullscreen = onToggleFullscreen,
                            outputDevices = app.playback?.outputDevices().orEmpty(),
                            selectedOutputDeviceId = app.playback?.currentOutputDeviceId(),
                            onSelectOutputDevice = { id -> app.playback?.setOutputDevice(id) },
)
                    }
                }
            }
        }
    }
}

/** 窗口几何 "x,y,w,h" 解析；全部为整数且宽高合理才认 */
private fun parseWindowGeometry(raw: String?): List<Int>? {
    if (raw == null) return null
    val parts = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size != 4) return null
    if (parts[2] < 300 || parts[3] < 300) return null
    if (parts[0] < -30000 || parts[1] < -30000) return null
    return parts
}

private const val KEY_WINDOW_GEOMETRY = "window.geometry"

/** 主题 + 全局图片加载上下文（封面图需要带 Referer 的 OkHttpClient） */
@Composable
private fun DesktopTheme(client: okhttp3.OkHttpClient, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.bilimusic.desktop.ui.LocalImageClient provides client,
    ) {
        BmTheme(content = content)
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

/** 把 AppLog 同时写到 ~/.bilimusic/app.log（超过 2MB 滚动成 app.1.log） */
private fun setupFileLogging() {
    runCatching {
        val dir = java.io.File(System.getProperty("user.home"), ".bilimusic").apply { mkdirs() }
        val logFile = java.io.File(dir, "app.log")
        if (logFile.exists() && logFile.length() > 2L * 1024 * 1024) {
            runCatching { logFile.renameTo(java.io.File(dir, "app.1.log")) }
        }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        com.bilimusic.app.util.AppLog.logger = { level, tag, message, error ->
            val err = error?.let { " | " + it.javaClass.simpleName + ": " + it.message }.orEmpty()
            val line = "[" + level + "] " + fmt.format(java.util.Date()) + " " + tag + ": " + message + err
            println(line)
            runCatching { logFile.appendText(line + System.lineSeparator()) }
        }
    }
}

private const val KEY_AUDIO_QUALITY = "audio_quality"
private const val KEY_SELECTED_PLAYLIST = "desktop.selectedPlaylist"