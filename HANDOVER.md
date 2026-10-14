# BiliMusic 桌面版 · 交接文档

> 面向下一个接手这个项目的智能体。读完这一份 + `desktop/README.md` 就可以直接开工。
> 最后更新：2026-10-14 · 对应提交 `bdd93b2`（另有 4 个文件在写本文档时刚提交）

---

## 0. 三十秒速览

**这是什么**：一个 Windows 桌面版 B 站音乐播放器（Compose Multiplatform + VLCJ），
和已有的安卓版**共用同一个纯 JVM `shared` 模块**（WBI 签名 / 接口 / 取流 / 音质 / 链接解析）。

**当前状态**：功能已完成并已发布，正在做体验打磨阶段。
- 仓库：`https://github.com/amns56780/bili-music-android`
- 桌面版 Release：`v1.0.0-desktop`（`bilimusic-setup.exe` 69MB + 便携版 zip 73MB）
- 安卓版 Release：`v1.1.1`
- 本机已安装并可用 ✓、登录可用 ✓、能正常播放 ✓

**接手者最该先看的**：第 3 节（环境，**不配好根本构建不了**）和第 8 节（踩坑清单，能省你几天）。

---

## 1. 项目结构

```
<REPO>/                        仓库根（本机为 C:\Users\amns2333\.dsh\dsh_orb\BiliMusic）
├── app/                       安卓端（Kotlin + Compose，74 文件 / ~13.2k 行）
├── shared/                    纯 JVM 共享层（32 文件 / ~2.0k 行）★ 两端共用
│   └── src/main/kotlin/com/bilimusic/app/
│       ├── data/remote/       WBI 签名、Retrofit API、DTO、Cookie、节流、错误
│       ├── data/player/       StreamUrlRepository（取流+缓存+失效重取）、AudioQualityProvider
│       ├── domain/model/      音质档位、播放模式、歌单来源类型等
│       └── util/              AppLog（可插拔日志）、BiliLinkParser、MonotonicClock
├── desktop/                   Windows 桌面端（23 文件 / ~4.2k 行）★ 本项目的重点
│   ├── src/main/kotlin/com/bilimusic/desktop/
│   │   ├── Main.kt            入口：Window / Tray / 全局热键 / 依赖装配（见下方逐文件说明）
│   │   ├── auth/              AuthController（扫码 + 粘贴 Cookie）
│   │   ├── data/              DesktopStorage / PlaylistStore / ImportService /
│   │   │                      LocalMusicScanner / LocalMusicController / DownloadService
│   │   ├── player/            VlcPlayer / PlaybackController / SleepTimer / GlobalHotkeys
│   │   ├── media/             MediaOpener（BV/av/链接 → 播放）
│   │   └── ui/                LoginScreen / HomeScreen / PlayerPanel / PlaylistSidebar /
│   │                          LocalMusicDialog / NativeTray / WindowsDarkTitleBar /
│   │                          AppTitleBar（已停用）/ QrImage / RemoteImage
│   ├── src/main/resources/icons/   BM 图标（.ico 5 档 + 192px png，与安卓端同源）
│   ├── tools/                 build-exe.ps1 / install-script.bat / uninstall-script.bat
│   └── README.md              功能清单与用法（面向用户）
└── gradle/libs.versions.toml  版本目录（Kotlin 2.0.21 / AGP 8.7.3 / ComposeMP 1.7.3 / VLCJ 4.8.2）
```

---

## 2. 功能完成度

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 登录 | ✅ | 扫码（二维码+轮询）/ 粘贴整段 Cookie 或只粘 SESSDATA |
| 在线播放 | ✅ | 6 档音质、4 种播放模式、队列、进度、音量 |
| 歌单导入 | ✅ | **四种来源全部可用**：收藏夹 / 稍后再看 / UP主投稿 / 合集系列 |
| 歌单管理 | ✅ | 新建/重命名/删除/排序 + 多选批量复制到、移动到（自动去重）|
| 本地音乐 | ✅ | 扫描目录**可自定义**（增删/恢复默认/持久化）+ 本地直连播放 + 导入歌单 |
| 定时关闭 | ✅ | 15/30/60/90 分钟，到点「等当前曲播完再停」|
| 系统托盘 | ✅ | 原生 AWT 菜单（中英双语），关窗口收进托盘继续播放 |
| 系统级全局媒体键 | ✅ | JNA 调 Win32 `RegisterHotKey`，实测注册 3/3 |
| 下载 / 离线缓存 | ✅ | 下载当前曲目，播放时离线优先 |
| 本地优先、免登录 | ✅ | 未登录直接进主界面，只有要网络歌单时才引导登录 |
| Windows 包 + Release | ✅ | 自解压 `bilimusic-setup.exe` + 便携版 zip，已发布 |
| msi/exe 安装包（WiX）| ❌ | 本机没装 WiX Toolset，见第 9 节 |
| 安卓端构建与测试 | ✅ | 上次验证：35 个单元测试 0 失败 + `assembleDebug` 通过 |

---

## 3. 环境准备（★ 不配好无法构建）

### 3.1 本机特殊配置（沙箱导致，**必须**）

本机 shell 受沙箱限制，`~/.gradle` 不可写、Kotlin 守护进程起不来，所以：

| 项 | 值 |
| --- | --- |
| `JAVA_HOME` | `C:\Program Files\Java\jdk-21` |
| `GRADLE_USER_HOME` | `C:\Users\amns2333\.dsh\dsh_orb\.gradle-user-home` ★ **必须设**，否则构建直接失败 |
| Gradle 包装器 | 已复制到上面那个 GRADLE_USER_HOME 里（287MB）|

`<GRADLE_USER_HOME>/gradle.properties`（**本机专用，不进仓库**）：

```properties
kotlin.compiler.execution.strategy=in-process
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
bilimusic.debugKeystore=C:/Users/amns2333/.dsh/dsh_orb/.keys/debug.keystore
bilimusic.tmpDir=C:/Users/amns2333/.dsh/dsh_orb/.run-tmp
bilimusic.skikoLibraryPath=C:/Users/amns2333/.dsh/dsh_orb/.run-tmp/skiko-native
```

为什么：
- `in-process` —— Kotlin 守护进程要写 `%LOCALAPPDATA%\kotlin\daemon\` ✗ 被拒 → 只能进程内编译
- `debugKeystore` —— `~/.android/debug.keystore.lock` 不可写 → 复制到工作区（**字节相同**，证书 SHA-256 不变 → 安卓升级安装不受影响）
- `skikoLibraryPath` —— skiko 的 `Library.unpackIfNeeded` 会抛 IOException → 预先把 `skiko-windows-x64.dll` + `icudtl.dat` 解到该目录。**注意：这里要填目录，不是文件路径**（填文件它还是会拼 `skiko-windows-x64.dll`）

### 3.2 工具与辅助文件（都在工作区，不进仓库）

| 路径 | 用途 |
| --- | --- |
| `<WORKSPACE>/.tools/7zip-extracted/` | 7-Zip 25.00 独立组件（`7z.exe` / `7z.dll` / **`7z.sfx`**）—— 打包自解压安装程序用。**没有安装到系统** |
| `<WORKSPACE>/.keys/debug.keystore` | 安卓 debug 签名（见上）|
| `<WORKSPACE>/.run-tmp/` | 临时目录 / skiko 原生库 |
| `<WORKSPACE>/.gradle-user-home/` | Gradle 家目录 |
| `<WORKSPACE>/_verify/push-api.ps1` | ★ **GitHub 推送脚本**（见 4.5）|
| `<WORKSPACE>/_verify/backup-bilimusic-data/` | 用户歌单备份（用户要求删除数据前我留的，可恢复）|

### 3.3 运行时依赖

- **VLC 必须已安装**（<https://www.videolan.org>）—— 播放内核依赖它
- **JRE/JDK 17+** —— 快捷方式会优先找 `JAVA_HOME` → `C:\Program Files\Java\jdk-{21..17}` → Adoptium/Zulu/Corretto → 最后才退回 PATH（**PATH 里第一个 javaw 可能是 Java 8，跑不起来**）

---

## 4. 命令速查

> 本机推荐用 bash（`ops_bash`）。下面同时给出 PowerShell 等价写法。
> **每次调用都要带上 JAVA_HOME 与 GRADLE_USER_HOME**，建议存成脚本。

### 4.1 构建与测试

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-21"
export GRADLE_USER_HOME="C:/Users/amns2333/.dsh/dsh_orb/.gradle-user-home"
cd "<REPO>"

./gradlew.bat :shared:test :app:testDebugUnitTest :app:assembleDebug :desktop:compileKotlin --console=plain
```
预期：`BUILD SUCCESSFUL`，35 个单元测试 0 失败。

### 4.2 运行桌面端（调试）

```bash
./gradlew.bat :desktop:run --console=plain
```

### 4.3 打包

```bash
# A) 便携版 zip（Gradle 自产，不需要任何外部工具）
./gradlew.bat :desktop:portableDist :desktop:portableZip --console=plain
#   → desktop/build/distributions/bilimusic-desktop-portable.zip

# B) 自解压安装程序 exe（用 7-Zip SFX，带 6 步自检）
pwsh -ExecutionPolicy Bypass -File desktop/tools/build-exe.ps1
#   → desktop/build/distributions/bilimusic-setup.exe
```

`build-exe.ps1` 每次都会：**强制重建负载** → 生成 stage → 写 `bm.bat` → 把中文模板转成 **GBK** → 7z 打包 → 拼 `7z.sfx` + config + 7z → **解包自检**（脚本齐全 / GBK 中文有效 / 卸载键已注册 / 包内 jar 与最新编译字节数一致）。

### 4.4 安装与卸载（静默测试用）

```bash
# 静默安装（空行=默认路径，N=不启动）——给 choice 喂输入，别用管道截断输出，会杀掉脚本
cmd //c "cd /d <REPO>\desktop\build\verify-extract && (echo.& echo N)| install.bat"

# 静默卸载（N=保留数据）
cmd //c "cd /d %LOCALAPPDATA%\Programs\bilimusic && echo N| uninstall.bat"
```

### 4.5 推送代码（★ 重要：git 直连不通）

本机 `git push` 走 schannel/openssl 都失败（`SEC_E_NO_CREDENTIALS` / `Connection was reset`），
**必须用分块 GitHub API 推送**：

```bash
pwsh -ExecutionPolicy Bypass -File "<WORKSPACE>/_verify/push-api.ps1" -Rev HEAD -MessageFile <消息文件> -MaxBlobs 3
```
- 每次最多 3 个 blob，输出 `REMAINING: n files still need blobs` 就**再跑一次**，直到 `PUSHED: <sha>`
- 消息文件**必须存在**；脚本会自动读 `gh auth token`
- 注意：远端 commit SHAs 与本地不同（API 创建的 commit 元数据不同），**内容一致**即可，不要为此纠结

---

## 5. 架构与数据流

### 5.1 分层

```
desktop (UI + VLC 内核)
   ↓ 只依赖 shared 的抽象
shared  (WBI 签名 / Retrofit API / DTO / 取流 / 音质 / 解析)
   ↑ 同样被 app(安卓) 使用
```

`shared` 里刻意做了两处抽象，好让桌面端复用：
- **`KeyValueStore`** —— 安卓用 `SecurePrefs`（加密），桌面用 `DesktopStorage`（明文 JSON）
- **`AudioQualityProvider`**（`fun interface`）—— 两端各自读自己的偏好设置

### 5.2 播放链路（桌面）

```
用户点播/粘链接
  → PlaybackController.playQueue(tracks, index)
  → 若已下载本地文件 → 直接本地播放（离线优先）
  → 否则 StreamUrlRepository.resolve(bvid, cid, quality)   [带缓存 + 403 失效重取 + 备用地址轮换]
  → VlcPlayer.play(url)   [媒体选项里带上 Referer/UA 与音量]
  → 事件回调更新 StateFlow → UI 重组
```

### 5.3 数据落盘（`~/.bilimusic/`）

| 文件 | 内容 | 备注 |
| --- | --- | --- |
| `settings.json` | 登录 Cookie、音质偏好、**音量** | ⚠️ **明文**（安卓端是加密的），要提醒用户 |
| `playlists.json` | 歌单与曲目（原子写：临时文件 + 改名）| |
| `local-dirs.json` | 本地音乐自定义扫描目录 | 空/不存在 = 用默认（音乐/下载/桌面）|
| `downloads/` + `downloads.json` | 离线音频 | |

---

## 6. desktop 逐文件说明

| 文件 | 职责 / 注意点 |
| --- | --- |
| `Main.kt` | 入口。`application { }` → `rememberDesktopApp(scope)` 装配全部依赖 → `Window` → `NativeTray` → `GlobalHotkeys` → `SleepTimer`。**AWT 中文字体在 `rememberDesktopApp` 里设置**（比 Tray 先执行）|
| `auth/AuthController.kt` | 扫码登录（二维码 + 轮询）、`submitCookie`（整段或只 SESSDATA）、自动恢复登录态 |
| `player/VlcPlayer.kt` | ★ VLCJ 封装。全局选项带 `:http-referrer` / `:http-user-agent`（**不加必 403**）、`:network-caching=300`、`--aout=mmdevice`。**音量走媒体选项 `:audio-volume=`**（见坑 6）|
| `player/PlaybackController.kt` | 队列 / 4 种模式 / 进度 / 音量持久化 / 失效重取。`setVolume` 会写 `settings.json` |
| `player/SleepTimer.kt` | 定时关闭 + 「等当前曲播完」|
| `player/GlobalHotkeys.kt` | JNA `RegisterHotKey`（播放/暂停、上一首、下一首）。**守护线程 + 消息循环**，注册失败只记日志 |
| `data/DesktopStorage.kt` | `KeyValueStore` 实现（`~/.bilimusic/settings.json`）|
| `data/PlaylistStore.kt` | 歌单 CRUD + 排序 + `copySongsTo` / `moveSongsTo`（去重）|
| `data/ImportService.kt` | 四种导入来源。收藏夹/UP主投稿**不带 cid**（逐条补，400ms 节流）；稍后再看/合集**自带 cid**（直接导）|
| `data/LocalMusicScanner.kt` | 扫描音频（`scan(dirs)` 支持传目录，深度上限 6、文件上限 5000）|
| `data/LocalMusicController.kt` | 自定义扫描目录（持久化 `local-dirs.json`）+ 导入「本地音乐」歌单 |
| `data/DownloadService.kt` | 下载 / 离线缓存 / 删除 |
| `media/MediaOpener.kt` | BV/av/链接/b23.tv → 取流播放 |
| `ui/HomeScreen.kt` | 顶栏（登录/未登录）+ 链接输入 + 三栏布局。**注意：中间栏不能用 `verticalScroll`**（见坑 3）|
| `ui/PlayerSidebar.kt` | 歌单列表 + 详情 + 多选批量复制/移动 |
| `ui/PlayerPanel.kt` | 封面/标题/进度/控制/**音量**。音量 Slider 用 `onValueChangeFinished` 才下发（见坑 7）|
| `ui/LoginScreen.kt` | 扫码页 + 粘贴 Cookie，有「返回，先听本地音乐」|
| `ui/NativeTray.kt` | ★ 原生 AWT 托盘（**逐项 `MenuItem.setFont`** + 中英双语标签）—— 不要改回 Compose 的 `Tray(menu=...)`（见坑 5）|
| `ui/WindowsDarkTitleBar.kt` | DWM 沉浸式深色标题栏（`DWMWA_USE_IMMERSIVE_DARK_MODE`，含旧系统回退）|
| `ui/AppTitleBar.kt` | **已停用的自绘标题栏**。保留是因为将来若真能拿到原生拖拽可复用；**现在不要接回去**（见坑 8）|
| `ui/QrImage.kt` / `RemoteImage.kt` | 二维码渲染 / 网络图片（按 URL 缓存）|
| `tools/build-exe.ps1` | ★ 打包脚本（**纯 ASCII**，别往里写中文！见坑 1）|
| `tools/install-script.bat` / `uninstall-script.bat` | 中文模板（**UTF-8 存储**，打包时脚本会转 GBK）|

---

## 7. 关键技术决策（为什么这么做）

| 决策 | 原因 |
| --- | --- |
| **播放内核用 VLC（VLCJ）** | B 站取流**必须带 `Referer` + `User-Agent`**，Java 生态里 JavaFX MediaPlayer / Java Sound / JLayer **都不支持自定义 HTTP 头**，换了就取不到流（除非自建本地代理）。VLC 内建支持、格式全（m4a/aac/flac）、HTTP 流稳定。**评估过换内核，结论是留在 VLC 但把用法改对。** |
| **抽出 `shared` 纯 JVM 模块** | WBI 签名、接口、DTO、取流、音质选择、链接解析逻辑两端完全一致，安卓端已有大量验证过的实现，不该重写 |
| **保留系统标题栏 + DWM 深色** | 自绘标题栏就必须自己搬窗口 → 主线程既收输入又重绘 → **必然卡顿**。参考 DeepSeek Harness（Electron `titleBarStyle: hidden` + `titleBarOverlay`）：**本质是保留系统窗口框架、拖动交给 DWM**。JVM 做不到"内容延伸到 caption 区还由系统拖"，所以取舍为：保留原生边框（拖动丝滑 + 白拿贴边/摇动/双击最大化）+ 把标题栏变深色 |
| **安装包用 7-Zip SFX，不用 jpackage/IExpress** | jpackage 需要 WiX（本机没装）且子进程写入可能被拒；IExpress 会**静默丢文件**（详见坑 2）；7-Zip SFX 可靠且**能用命令行解包验证** |
| **托盘菜单用原生 AWT 自建** | Compose 的 `Tray(menu=...)` 底层是原生菜单，字体与编码都不受 Compose 控制，中文乱码（详见坑 5）|
| **音量写进媒体选项** | VLC 每个新媒体开始播放时都会重置音量（详见坑 6）|

---

## 8. 踩坑清单（★ 血泪，先读这节能省几天）

### 坑 1 · 编码（本项目最大的时间黑洞）

- **`.bat` 含中文必须存 GBK**：cmd 在中文系统按 ANSI(936) 逐行解析，UTF-8 的中文会变乱码命令、连 `:label`/`choice` 都被拆坏。**在文件里写 `chcp 65001` 救不了**（读到那行时前面的中文已经解析坏了）。
- **`.ps1` 不要写中文**：无 BOM 的 UTF-8 会被 PowerShell 当 GBK 读 → **脚本自身解析失败**。要么纯 ASCII，要么**写完后加 UTF-8 BOM**（`[System.IO.File]::WriteAllText($p, $t, [System.Text.UTF8Encoding]::new($true))`）。本仓库的 `build-exe.ps1` 刻意保持 **ASCII-only**。
- **模板与产物分离**：中文放在 `desktop/tools/*.bat`（UTF-8 存储），由 `build-exe.ps1` 在打包时转 GBK——这样脚本永远是 ASCII。
- **模板要保持 UTF-8**：我一度手动预转过 GBK，结果打包时**二次转码**把中文变乱码。只在一处转。
- **不要用 PowerShell 文本 cmdlet 改源码**（`Get-Content | Set-Content` 曾把 `app/build.gradle.kts` 搞成乱码 + 把 `signingConfig` 吞进注释，导致 release APK 未签名）。

### 坑 2 · IExpress 会静默丢文件

用 IExpress 打的 exe，用户双击报 `创建进程 ...\IXP000.TMP\install.bat 时出错：系统找不到指定的文件`。
**根因**：SED 文件清单里**长文件名或多级目录**会让 IExpress 丢文件（改成 8.3 短名也没救）。
**结论**：不要再用 IExpress，已全部换成 **7-Zip SFX**（`7z.sfx` + config + 7z 拼接）。

### 坑 3 · Compose：`LazyColumn` 不能嵌在 `verticalScroll` 里

报 `Vertically scrollable component was measured with an infinity maximum height constraints` → **点确定即闪退**（用户实测）。
修法：去掉外层 `verticalScroll`，改用 `Modifier.weight(1f)` 约束高度。

### 坑 4 · 打包/安装的"看起来装了其实没装"

两个独立原因，**都踩过，都表现为"功能没变化"**：
1. **负载没重建** → 打到包里的是旧 jar。`build-exe.ps1` 现在**每次强制重建**并打印 `desktop.jar` 构建时间。
2. **旧 jar 残留** → 启动用 `-cp "*"` 会加载目录下**所有** jar，旧 jar 可能抢先。安装脚本现在**先 `del /q "%TARGET%\*.jar"`** 再复制。
3. 另外：**安装前要结束正在运行的实例**，否则 jar 被占用，`del` 失败 → 又回到原因 2。安装脚本已内置（只结束命令行含 `com.bilimusic.desktop.MainKt` 的 javaw，**不碰 Gradle 守护进程**）。

**验证手段**（务必用）：打包后自检会比对"包内 jar 字节数 == 刚编译的 `desktop.jar` 字节数"，安装后再比一次。

### 坑 5 · 托盘菜单中文乱码

- **字体探测结论**：系统字体完全正常（`Microsoft YaHei UI` 存在、`canDisplayCJK=true`、109 个字体），所以**不是缺字形**。
- `UIManager.put("MenuItem.font", ...)` **对原生菜单无效**；唯一有效的是**逐项 `MenuItem.setFont()`**。
- 当前实现：`NativeTray.kt` 用 `SystemTray` + `TrayIcon` + `PopupMenu`，逐项 `setFont`，标签用**中英双语**（万一中文仍异常，英文部分仍可读）。
- 启动参数带 `-Dfile.encoding=GBK`（让 `file.encoding` 与中文系统的 `sun.jnu.encoding=GBK` 对齐）。**不要改成 UTF-8**（Java 21 默认就是 UTF-8，反而会不一致）。
- 英文已验证正常 ✓；中文是否完全正常**未最终确认**，若仍乱码 → 改用 Compose 自绘弹层做托盘菜单（Compose 渲染中文一直正常）。

### 坑 6 · VLC 每次播放都会重置音量（音量总是回 80）

**这是 VLC 的既定行为**，不是代码写漏。参考：
[VLC Android 同类问题](https://mailman.videolan.org/pipermail/android/2021-July/019976.html)、
[simplex-chat per-app volume reset](https://github.com/simplex-chat/simplex-chat/pull/6852)、
[StackOverflow: vlcj 播放前设音量](https://stackoverflow.com/questions/62307382/vlcj-how-to-change-the-volume-of-audio-before-playing-it)。

**正确做法**（已实施）：把音量写进**媒体选项**，让音量随媒体一起生效：
```kotlin
val volumeOption = ":audio-volume=" + String.format(Locale.US, "%.2f", desiredVolume / 100.0)  // 1.0 == 100%
player.media().play(url, volumeOption)
```
- `--audio-volume` 是 **0.0~2.0 的浮点**，所以要 `desiredVolume/100.0`
- **必须用 `Locale.US` 格式化**：中文区域会输出 `0,20`，VLC 解析失败
- 双保险：`playing` 事件回调里再 `audio().setVolume(desiredVolume)` 一次

### 坑 7 · UI 操作"过一会才生效"（音量尤其明显）

Slider 的 `onValueChange` **每拖动一帧**都调 `player.audio().setVolume()` → 跨语言调用积压 → 声音滞后于手。
**修法**：`onValueChange` 只更新本地 draft（数字即时跟手），**`onValueChangeFinished` 才下发 VLC**。
> 检查其他控件（进度条、模式切换、切歌按钮）是否有同类"每帧调 JNI/网络"问题。

### 坑 8 · 自绘标题栏 + 自己搬窗口 = 必然卡顿

用 Compose 手势逐帧 `window.location = ...` 拖动，无论怎么优化都会卡（主线程既处理输入又重绘整个界面），而且曾因"用增量累加"产生**正反馈震荡（鬼畜抖动）**。
**结论**：保留系统标题栏（拖动交给 DWM）。参考第 7 节的决策说明。

### 坑 9 · 快捷方式启动失败（Java 8）

`where javaw` 取到 PATH 里第一个，可能是 **Oracle Java 8** → Compose 需要 17+ → 点开无反应。
安装脚本的查找顺序：`JAVA_HOME` → `C:\Program Files\Java\jdk-{21..17}` → Adoptium/Zulu/Corretto/Microsoft → 最后才 PATH。

### 坑 10 · JNA：`User32` 没有 `ReleaseCapture`

编译报 `Unresolved reference 'ReleaseCapture'`。需要自己声明接口：
```kotlin
private interface User32Ex : StdCallLibrary { fun ReleaseCapture(): Boolean }
private val user32Ex = Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
```
（`FindWindow` / `SendMessage` 是现成的。）
另外 `GetMessage` 返回 **int** 不是 boolean（`while (GetMessage(...) != 0)`）。

### 坑 11 · 删除/移动前的安全检查（用户明确要求）

用户说过「**一定不要误删**」。执行删除时：
- 逐个做**绝对路径精确比对**（`[System.IO.Path]::GetFullPath($p) -eq $p`），用 `-LiteralPath`，**不用通配符**
- 维护保护路径黑名单（用户主目录、盘根、`Programs`、`Downloads` 等）
- 结束后**复查"不该丢的"**（仓库、Downloads、桌面、父目录）
- 结束进程时**严格按命令行匹配**（`com.bilimusic.desktop.MainKt`），绝不 `taskkill /im javaw.exe`

### 坑 12 · 用户隐私

- 用户对此敏感：**API token 只用一次、不落盘**；不要把用户 UID、账号名、本机绝对路径写进公开仓库
- 不要截全屏（曾暴露用户其它窗口）——只截自己的窗口，或干脆不截图
- 删除用户数据前**先备份**（已有 `_verify/backup-bilimusic-data/`）

---

## 9. 已知问题与待办（按优先级）

### P0 · 4 个文件尚未提交（写本文档时刚提交，请确认 `git status` 是否干净）
`Main.kt` / `PlaybackController.kt` / `VlcPlayer.kt` / `PlayerPanel.kt` —— 音量相关的连续修复。

### P1 · UI 太紧凑（用户已选定方案 B，**待实施**）
用户的回答是：「**A + 重排布局（中间更宽敞）**」。具体：
1. 间距放宽：卡片间距 `14 → 22dp`、卡片内边距 `12 → 18dp`、列表行高加大、`labelSmall → bodySmall`
2. **重排**：把「播放队列」从中间第三列改成**可折叠的右侧栏**（默认展开，折叠后中间的歌单详情 + 播放器占满宽度）
3. 播放器面板加高、封面放大
> ⚠️ 动布局前先读坑 3（`LazyColumn` 与 `verticalScroll` 不能嵌套）。

### P1 · 交互延迟（部分已修，需继续排查）
- ✅ 音量：已定位为"每帧调 JNI 积压"，改为 `onValueChangeFinished` 下发
- ⏳ 用同样思路检查：进度条拖动、切歌/播放暂停按钮、模式切换
- 怀疑方向：`collectAsState` 订阅范围过大（顶层订阅 → 任何状态变化重建整个界面）、封面图重复加载
- **验证手段**：用模拟鼠标点击 + 计时量化前后差异（不要靠感觉）

### P2 · 播放失败提示改进
`VlcPlayer.kt` 的 `error` 回调现在写死一句「这个地址播不了（可能已过期），正在重试」。
用户反馈"经常出现"，且**已确认不是账号问题**（登录正常、能播）。计划：
1. 提示里附**具体原因**（HTTP 403 / 地址过期 / 其它）
2. **自动降一档音质重试**（192K 失败 → 128K）
3. 用户可能仍会遇到：大会员专享 / 版权地区限制 / 稿件失效 —— 这些只能提示，不能修

### P2 · msi/exe 安装包（WiX）
本机没装 WiX Toolset，`packageMsi` / `packageExe` 走不通。当前用 7-Zip SFX 自解压 exe 替代（已注册到「应用和功能」，可正常卸载）。
若要做真正的 msi：装 WiX v3，然后 `./gradlew.bat :desktop:packageMsi`。

### P3 · 其他
- 本地音乐不解析 ID3（标题取文件名、艺术家取文件夹名）
- 托盘 exe 自身图标是 7-Zip 的（7z.sfx 限制；应用窗口/托盘/快捷方式**都已是 BM 图标**）
- 真·全局媒体键已实现，但若被其它播放器占用会注册失败（只记日志，不影响使用）

---

## 10. 用户偏好与协作方式（★ 很重要）

- **语言**：用户是中文用户，**所有回复与文档用中文**；用户明确要求**思考过程也用中文**
- **红线**：
  - **绝不要删除用户电脑上的任何数据**，删除前必须先列出清单并**向他确认**
  - 隐私敏感：不要泄露账号/UID/本机路径到公开仓库
- **协作节奏**：用户会**亲自实测**并给截图反馈。UI/手感类问题**我看不到**，必须靠他的截图；但**能用工具自己验证的（编译、解包、字节比对、字体探测、模拟鼠标）一定要先验证再下结论** ✗ 我在这个项目里因为"盲改"连续踩了几轮坑，教训是：
  - **先测量，再改**（字体探测、字节比对、DSH 源码对比都是这样找到根因的）
  - **改完必须验证**（编译 + 打包 + 自检 + 安装 + 让用户看）
  - 用户两次关键提示（"接管鼠标测试"、"看 dsh 怎么实现"）都比我自己猜测有效
- **改动纪律**：改动大时**先给方案让用户选**（本项目成功用过 `ask_user_question` 确认 UI 方案）
- 用户 Windows 桌面路径是 **`E:\desktop`**（被重定向过，不是默认的 `%USERPROFILE%\Desktop`）—— 涉及桌面的脚本要用 `[Environment]::GetFolderPath('Desktop')` 取

---

## 11. 验证清单（每次改动后照做）

```bash
# 1. 编译 + 测试
./gradlew.bat :shared:test :app:testDebugUnitTest :app:assembleDebug :desktop:compileKotlin --console=plain
#    预期：BUILD SUCCESSFUL；35 个单元测试 0 失败

# 2. 打包（自带 6 步自检，会验证包内 jar 与最新编译字节一致）
pwsh -ExecutionPolicy Bypass -File desktop/tools/build-exe.ps1
#    预期：[ok] all scripts present, GBK Chinese valid, uninstall key registered
#          [ok] packaged app matches the fresh build (NNNNN bytes)

# 3. 安装后核验
ls "$LOCALAPPDATA/Programs/bilimusic" | wc -l          # 预期 59
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\bilimusic" /v UninstallString
#    预期：指向 <安装目录>\uninstall.bat，且该文件真实存在
```

**装机验证点**：不用登录能进主界面 ✓ / 切歌单不闪退 ✓ / 音量拖到 20 松手立即生效且切歌保持 ✓ /
托盘右键菜单可读 ✓ / 标题栏是深色系统栏且拖动顺滑（能贴边分屏）✓ / 播放依赖 VLC ✓

---

## 12. 发布流程

```bash
# 桌面版：打好的包在 desktop/build/distributions/
gh release upload v1.0.0-desktop desktop/build/distributions/bilimusic-setup.exe --clobber
gh release upload v1.0.0-desktop desktop/build/distributions/bilimusic-desktop-portable.zip --clobber

# 注意：gh 必须在仓库目录下运行（否则报 "not a git repository"）
```
- 桌面版 Release：`v1.0.0-desktop`
- 安卓版 Release：`v1.1.0` / `v1.1.1`（历史）
- 代码推送**不要用 `git push`**（不通），用 `_verify/push-api.ps1`（见 4.5）

---

## 13. 快速自检问答（接手前测一下自己）

1. 不设 `GRADLE_USER_HOME` 会怎样？→ 构建直接失败（坑 / 第 3 节）
2. 打包后怎么确认**没有**把旧代码打进包？→ 自检里比对包内 jar 与最新 `desktop.jar` 字节数（坑 4）
3. 用户说"装了没变化"，先查哪三件事？→ 负载是否重建 / 旧 jar 是否残留 / 安装前是否结束了运行中的实例（坑 4）
4. 音量为什么总回 80？→ VLC 每个新媒体都重置音量，必须走媒体选项（坑 6）
5. 为什么不能用自绘标题栏拖动？→ 主线程既收输入又重绘，必卡（坑 8）
6. 中文 `.bat` 用什么编码？为什么？→ GBK，因为 cmd 按 ANSI 解析（坑 1）
7. 代码怎么推上去？→ `_verify/push-api.ps1`，分块 API 推送（4.5）
