# bilimusic（B站音源安卓播放器）

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="88" alt="bilimusic 图标">

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/amns56780/bili-music?label=Release&color=blue)](https://github.com/amns56780/bili-music/releases/latest)
[![Download APK](https://img.shields.io/badge/Download-APK%2017.5MB-success.svg)](https://github.com/amns56780/bili-music/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%2026%2B-brightgreen.svg)](#四如何构建)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg)](https://developer.android.com/jetpack/compose)

> 应用名：**bilimusic**（包名 `com.bilimusic.app`）· 图标：自绘的 **BM** 字母标（浅灰超椭圆底 + 圆头等粗描边，48px 下仍可辨认；含 Android 13+ 主题图标单色层）

一个**本地自用**的安卓音乐播放器：登录 B 站账号 → 把收藏夹 / 稍后再看 / UP主投稿 / 合集导入成歌单 → **只取音频流**播放，后台稳定不断播。

> ⚠️ **非官方项目**，与哔哩哔哩官方无关；使用非官方公开接口，仅供个人学习交流，**禁止商业使用**。
> 侧载安装，不上架应用商店。

**已实现**：扫码/Cookie 登录 · 4 种来源导入歌单（含合集/分P 选集）· WBI 签名取流（URL 过期自动重取）·
Media3 播放内核 + 通知栏/锁屏/蓝牙线控 · **刻意不申请音频焦点**（可与其他 App 同时出声）·
4 种播放模式 + 队列可视化 + 定时关闭 · **离线缓存**（cacheKey 用 `bvid-cid-音质`）· 6 档音质选择 · Glance 桌面小组件 ·
**歌单间批量复制/移动** · **本地音乐**（扫描系统媒体库，可直接播放或导入歌单）。

### v1.1 新增（使用反馈修复 + 新功能）

| 项 | 说明 |
| --- | --- |
| 🐞 修复「随机播放听歌，一首播完莫名停下」 | 根因：定时关闭的 `waitingForTrackEnd` 会持久化，进程在「等本曲播完」期间被杀就残留在 DataStore 里，之后**任何一首歌自然播完都会触发定时停止**（不只是随机播放）。修法：①服务重建时不再恢复该状态（正在倒计时的定时仍会恢复）②用户主动开始新播放时清除遗留标志 ③洗牌顺序按播放列表长度重建。回归测试 `StaleSleepTimerRegressionTest`（3 例，含端到端「曲末必须续播」） |
| ✨ 歌单间批量复制 / 移动 | 歌单详情页**长按曲目进入多选**（全选 / 反选），底部「复制到歌单 / 移动到歌单」；目标歌单自动排除当前歌单，重复曲目自动跳过并如实提示（「已复制 0 首：5 首已在目标歌单，跳过重复」）。仓库层测试 `PlaylistCopyMoveTest` 用临时歌单验证四种情形并自动清理 |
| ✨ 本地音乐（当本地播放器用） | 扫描系统媒体库（`READ_MEDIA_AUDIO`，**只读音频、不申请写入权限**）：**点一行直接播**（进的是同一套播放器，队列 / 随机 / 定时 / 小组件全都能用），也能**勾选导入歌单**跟 B 站曲目混排。本地文件走**不缓存**的直连链，不占离线缓存空间；打不开的条目自动跳到下一首 |
| 🔧 数据库 | `song` 表新增 `localUri`（Room v1→v2 迁移），并**去掉 `fallbackToDestructiveMigration`** —— 用户歌单是真实数据，升级宁可报错也不能静默清库 |

### v1.1.1 新增（继续修反馈）

| 项 | 说明 |
| --- | --- |
| 🐞 修复「**有时候**随机播放着，某首歌放完突然暂停」 | 根因：定时关闭的截止点用 `SystemClock.elapsedRealtime()`（**开机以来毫秒数，重启会归零**），而持久化时只存了这个数。手机重启后它会变成"未来某个时刻"→ App 一启动就恢复出一个**幽灵定时** → 几小时后"到点"→ 设「等本曲播完」→ 下一首歌自然播完就暂停。这解释了为什么是"有时候"（只在那次偏差恰好落在使用时段内时发生）。修法：保存定时时同时记下「保存时的开机时长」与「开机时刻标记」，恢复时两道校验（单调时钟不能回退 + 开机标记不能漂移），任一不成立就丢弃。旧版本存下的记录没有这些信息，一律保守丢弃。测试 `SleepTimerRestoreTest`（6 例） |
| 🐞 修复「默认模式是顺序播放」 | 默认改成**列表循环**（`PlayMode.DEFAULT`），随机播完最后一首自动从头继续；`playSongs` 也不再硬写模式，而是沿用用户保存的选择（以前选了列表循环，一点「播放全部」就被改回顺序播放） |
| 🎨 区分「顺序播放」与「列表循环」图标 | 以前两个模式**用同一个 `Repeat` 图标**；现在顺序播放用「列表⇢播放」图标、列表循环用环形箭头，切换时再给一句「播放模式：xxx」的文字反馈 |
| 🐞 修复「播放服务断开后 UI 冻住」 | `startTicker()` 检测到控制器断开就 `break` 退出循环 → 之后即使重连也永远不再刷新状态（进度条、模式都不更新）。改成不退出循环并**主动重连** |
| 🐞 修复「断开时切换播放模式静默失效」 | `setPlayMode` 在未连接时直接 return；现在**先落盘保存**再尝试应用，连上后 `connect()` 会应用保存的模式 |


> **踩坑记录（本地播放 `Source error`）**：一开始用 `CacheKeyFactory` 返回 `null` 来跳过本地文件缓存，
> 结果 Media3 1.5 的 `CacheDataSource` 拿到 null key 会直接抛错 —— 表现为**所有本地文件都播不了**。
> A/B 测试定位后改成在数据源层按 URI 分流（`SchemeRoutingDataSource`），线上走缓存链、本地走直连链。

---

## 一、当前进度（分阶段交付）

| Phase | 内容 | 状态 |
| --- | --- | --- |
| 1 | 工程骨架、主题、导航、Hilt、Room、Gradle 配置 | ✅ 已完成 |
| 2 | OkHttp/Retrofit/WBI/Cookie 管理 + 扫码登录 + Cookie 保底 | ✅ 已完成 |
| 3 | 歌单导入（收藏夹 / 稍后再看 / 合集 / 手动）+ 歌单列表与详情 | ✅ 已完成 |
| 4 | 播放核心：取流 + ExoPlayer + 播放页 + MiniPlayer | ✅ 已完成 |
| 5 | MediaSessionService + 通知栏 + 锁屏 + 线控 + 关闭音频焦点 | ✅ 已完成 |
| 6 | 播放模式 + 队列可视化 + 定时关闭 + 选集页 | ✅ 已完成 |
| 7 | 离线缓存 + 音质选择 + 桌面小组件 + 全面适配打磨 | ✅ 已完成 |

**Phase 1 已可用的功能**：

> **真机验证结论（2026-10-03）**：已在 Android 16 真机（API 36 / arm64-v8a）实测通过 ——
> 冷启动、空状态、新建歌单、重命名、删除（含二次确认）、`am force-stop` 后冷启动数据仍在、
> 深色模式 + 动态取色 + edge-to-edge 显示正常、详情页正确隐藏底部导航，**全程无 FATAL EXCEPTION**。
> 验证截图见工作区 `_verify/phase1_*.png`。

- 歌单列表（首页）：网格展示、空状态引导、新建歌单、重命名、删除（含二次确认）
- 歌单详情页：封面首图 + 曲目数 + 总时长 + 真实曲目列表 + 单曲「从歌单移除」+ 歌单重命名/删除
- 设置页：关于、已知限制、免责声明、版本信息
- 底部导航（歌单 / 设置）、Material 3 主题（Android 12+ 动态取色、跟随系统深色模式）、edge-to-edge

**Phase 2 已可用的功能（登录）**：

> **真机验证结论（2026-10-03）**：扫码二维码渲染、180 秒倒计时、2 秒轮询（logcat 实测）、
> 手动 Cookie 登录（真实账号，设置页显示头像/昵称/UID）、`am force-stop` 后冷启动仍是登录态、
> 退出登录后彻底清空并回到登录页，**全程无 FATAL EXCEPTION**。验证截图见 `_verify/phase2_*.png`。
> 自动化：`LoginCookieTest`（仓储层真实登录）、`LoginScreenUiTest`（Compose UI 全链路，
> 绕过中文输入法直接输入整段 Cookie 并点按钮）均可通过 `am instrument -e cookie '...'` 复现。

- 扫码登录：180 秒有效期 + 进度条倒计时、`等待扫码 → 已扫码请确认 → 登录成功 → 二维码已过期` 四态文案、
  2 秒轮询（失败指数退避至 16 秒，连续 5 次失败才提示）、过期自动刷新新码、手动刷新按钮
- 手动粘贴 Cookie 兜底：支持整段 Cookie（`SESSDATA=xxx; bili_jct=yyy`）或只粘 SESSDATA 的值，含「如何获取 Cookie」折叠说明
- 登录态加密持久化（EncryptedSharedPreferences，Keystore 不可用时降级并记日志）；启动校验登录态，
  失效时清空并提示「登录已过期，请重新登录」；网络不通时用账号快照保持已登录，不把用户无故踢下线
- 设置页显示头像 / 昵称 / UID / 大会员标记 + 退出登录（二次确认，彻底清空 Cookie 与账号快照并回到登录页）
- 网络底座：统一 UA/Referer/Origin 注入（音频流复用同一个 OkHttpClient，避免 403）、CookieJar 自动收发 Cookie、
  WBI 签名（mixin_key 日缓存）、错误码 → 中文提示映射、超时 10s/20s、失败重试与退避（-352/-412 不重试）

**Phase 3 已可用的功能（歌单导入）**：

> **真机验证结论（2026-10-03）**：收藏夹列表（WBI 签名真实生效，读到 12 个收藏夹，含 9647 个视频的大收藏夹）、
> 收藏夹导入（成功 5 条 + 报告）、稍后再看导入（**99 条全部成功**，进度「第 15 / 共 99 条」实时刷新）、
> UP主投稿（自己账号 6 条全部成功，未触发风控）、合集识别（真实数据解析出「合集 · 第 725 集 / 共 850 集」等）、
> 手动粘贴 BV 加单曲（含重复去重提示）、节流实测相邻请求严格 400ms，全程无 FATAL EXCEPTION。

- 导入页 4 个来源各一个 Tab：收藏夹 / 稍后再看 / UP主投稿 / 合集
- 收藏夹：列出全部收藏夹（名称、视频数、私密锁标记），可勾选多个、全选 / 全不选；
  分页拉取（单页 20 条），逐条取视频详情拿 cid / 时长 / 封面 / UP主名后落库；进度显示「第 n / 共 m 条」
- 稍后再看：一键导入成一个歌单
- UP主投稿：输入 mid 或空间链接；**遇到 -352 / -412 立即降级**为「该来源受 B 站风控限制，请改用收藏夹或合集导入」
  并把按钮置灰，绝不死磕重试
- 合集 / 分P：粘贴 BV / AV / b23.tv 短链或整段分享文案，自动识别所属合集（`ugc_season`）拉全部集数，
  多 P 视频导入全部分 P，都不是就当单曲；顺序、集数、第几集都按原始顺序落库
- 手动加单曲：歌单详情页右上角「添加单曲」，支持 BV / AV / 短链 / 分享文案，重复自动跳过并提示
- 导入报告：成功 N 条 / 跳过失效 M 条 / 重复跳过 K 条 / 读取失败 J 条，并可直接跳到该歌单
- 反风控：所有批量请求相邻间隔 400ms；同一个收藏夹/合集重复导入会复用同一个歌单做增量去重
- 去重：`Song` 表的 `(bvid, cid, playlistId)` 唯一索引兜底

**Phase 4 已可用的功能（播放内核）**：

> **真机验证结论（2026-10-03）**：点「播放全部」后**真实出声** ——
> `dumpsys audio` 显示 `AudioPlaybackConfiguration … state:started … usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC … sampleRate=48000`；
> `dumpsys media_session` 显示 `state=PLAYING(3) position=… buffered=…`，metadata 为真实曲目；
> 播放页显示封面 / 标题 / UP主 / **「第 1 首 / 共 5 首」** / 进度 00:07 / 07:07 / 音质标签 **192K**；
> 暂停、下一首、拖动进度条（跳到 03:05）、模式循环切换到「随机播放」全部生效；
> 返回后 **MiniPlayer** 常驻底部并正确显示「第 2 首 / 共 5 首」。
> 顺带验证了 Phase 5 的关键项：通知栏（Android 13+ 媒体卡片，含封面/标题/上一首/暂停/下一首）、
> `KEYCODE_MEDIA_PLAY_PAUSE` 与 `KEYCODE_MEDIA_NEXT` 线控按键**真的能控制播放**。

- **取流**：`x/player/wbi/playurl`（WBI 签名，`fnval=4048` 一次拿全格式）→ 签名类失败自动强刷 mixin_key 重试 →
  仍失败退到不带 WBI 的 `x/player/playurl`；`dash.audio[]` 里 camelCase / snake_case 两种字段名都能接
- **120 分钟过期处理**（任务书 4.3 的坑）：
  - 解析 URL 里的 `deadline` 参数，带 5 分钟安全余量的内存缓存，快过期就重新取流
  - 播放器报 403/401 时清掉缓存，下一次重试自动重新取流（自定义 `LoadErrorHandlingPolicy` 让 403 快速重试）
  - 保留 `baseUrl` + `backupUrl[]`
- **延迟取流**：`MediaItem` 里放的是虚拟 URI `bilimusic://audio/{bvid}/{cid}`，
  由 `ResolvingDataSource` 在**每次 open 时**解析成真实地址 —— 所以导入 99 首不需要 99 次取流，
  而几小时后才播到的那首也一定是新鲜地址
- **音频数据源**：`OkHttpDataSource`（复用同一个 OkHttpClient，自带 Referer/UA，防 403）→ 虚拟 URI 解析 → 403 失效重取
- **ExoPlayer 关键配置**（任务书 FR-6）：`setAudioAttributes(attrs, handleAudioFocus = false)` 不接管音频焦点、
  `setHandleAudioBecomingNoisy(false)` 拔耳机不暂停、`setWakeMode(C.WAKE_MODE_NETWORK)` 息屏不断流、seek 步长 10 秒
- **播放页**：封面、标题、UP主、**「第 X 首 / 共 N 首」大字常驻**、可拖动进度条、当前/总时长、
  上一首/播放暂停/下一首、播放模式按钮（顺序 → 列表循环 → 单曲循环 → 随机）、当前音质标签；
  横屏/平板自动变左右分栏
- **MiniPlayer**：除播放页外所有页面底部常驻，封面 + 标题 + UP主 + 「第 X 首 / 共 N 首」+ 播放暂停 + 下一首
- **音质**：自动档按 192K → 132K → 64K 降级链选择（实测拿到 192K），指定档位拿不到时自动降级
- **播放服务**：`PlaybackService`（MediaSessionService）是播放器的唯一真源，UI 通过 `MediaController` 连接；
  固定 seed 的洗牌顺序保证同一次会话内切歌不会重排

**Phase 5 已完成（后台播放 / 通知 / 线控 / 音频焦点）**：

> **真机验证结论（2026-10-03）**：
> ① `dumpsys audio` 的音频焦点栈里**完全没有本 App** —— 确实没申请音频焦点；
> ② **两路声音同时出声**：本 App（uid 10035，48kHz）与抖音（uid 10326，44.1kHz）的 AudioTrack **同时 `state:started`、两边 `mutedState:none`**，
> 本 App 播放位置在 12 秒内连续推进 12012ms，**没有暂停、没有降音量**（FR-6 验收通过）；
> ③ 通知栏出现 Android 13+ 媒体卡片（封面 / 标题 / UP主 + 上一首 / 暂停 / 下一首，`category=transport actions=3 vis=PUBLIC`）；
> ④ `KEYCODE_MEDIA_PLAY_PAUSE` 让暂停→播放、`KEYCODE_MEDIA_NEXT` 切到下一首（耳机/蓝牙线控路径打通）。

- `MediaSessionService` 承载播放，`MediaSession` 独立于音频焦点，通知栏/锁屏/线控不受影响
- 设置页新增：**后台播放保护**（引导关闭电池优化，回到页面自动刷新状态）+ **通知权限未授权时的「去授权」入口**
- 通知权限在**用户第一次真正开始播放时**才请求，不在一进 App 就弹

**Phase 6 已完成部分（播放模式 / 队列 / 定时关闭）**：

> **真机验证结论（2026-10-03）**：
> 队列 BottomSheet 按真实播放顺序编号 1..N、当前项高亮 + 呼吸动画指示、点击第 3 项成功跳到第 3 首；
> 定时关闭面板预设 15/30/45/60/90/120 + 自定义 1 分钟启动后，播放页出现「⏱ 剩余 …」倒计时；
> **FR-5 核心正确性用例（`SleepTimerDeadlineTest`）通过**：到点后本曲**完整播完才停止**、标签变「本曲播完后停止」、
> 期间音乐不中断，且自然播完后**不会多播下一首**。

- **播放模式**：顺序 / 列表循环 / 单曲循环 / 随机（固定 seed，同一次会话内顺序稳定，切歌不重排）
- **队列页**：`ModalBottomSheet` 列出真实播放顺序（含 shuffle 后的顺序），行首序号、当前项高亮 + 动画电平指示、
  点击跳转、长按上移/下移、左滑移除
- **定时关闭**：预设 + 自定义（1~720 分钟）+ 「播完当前歌曲后停止」开关（默认开）+ 取消定时；
  用 `SystemClock.elapsedRealtime()` 计时；到点只设标志，真正停止发生在
  `onMediaItemTransition(reason == AUTO)` 与 `onPlaybackStateChanged(STATE_ENDED)`；
  用户重新点播放会取消定时停止并提示；到期时刻与等待状态持久化，App 被杀后可恢复；
  通知栏加一行剩余时间（`subText`）
- **到点行为踩过的坑**（已修复并写进代码注释）：中途拖动进度条会产生「缓冲 → 继续播放」的瞬时状态，
  早期版本据此误判为「用户重新播放」而把定时取消了；现在改为
  ①UI 播放按钮显式取消 + ②只有暂停超过 1.5 秒且不在切歌后 3 秒内才算用户主动恢复
- **FR-3 选集页**（`ui/collection/`）：歌单详情里对有合集/分P的曲目显示「选集（共 N 集）」入口 →
  - 逐条列出全部分P/所有集：序号、标题、时长、Checkbox，**默认全选**
  - 快捷操作：全选 / 全不选 / 反选
  - 底部固定操作栏：**「已选 n / 共 m」** + 合计时长 + `播放选中` / `加入歌单`；
    一集都没选时两个按钮**置灰并提示「请至少选择 1 集」**
  - **勾选持久记忆**：改动即写 Room（`collection_selection` 表，按 `playlistId + collectionKey + epCid` 唯一），
    退出页面、切歌单、重启 App 后都原样恢复；首次进入就把「默认全选」这份记忆落库
  - **播放选中**：队列**只包含勾选项**，且按 `pageIndex` **保持合集/分P的原始相对顺序**（不是勾选顺序）
  - **队列页能看出是合集的哪几集**：`MediaMetadata.subtitle` 带上「合集 · 第 N 集 / 共 M 集」

> **FR-3 真机验证（2026-10-03）**：用真实合集「炫神の切片！」（639 集）实测 ——
> 默认「已选 639 / 共 639」；点「反选」→「已选 0 / 共 639」+「请至少选择 1 集」+ 两个按钮置灰；
> 退出歌单再重进，勾选状态原样恢复（持久记忆通过）；
> **先勾第 3 集、再勾第 1 集 → 播放页显示「第 1 首 / 共 2 首」且从第 1 集开始**（只含勾选项 + 原始顺序通过）；
> 队列页显示「合集 · 第 1 集 / 共 639 集」「合集 · 第 3 集 / 共 639 集」。

**Phase 7 已完成（离线缓存 / 音质选择 / 桌面小组件 / 适配打磨）**：

> **真机验证结论（2026-10-03）**：
> 音质选择面板 **6 档全在**（自动 / 192K / 132K / 64K / Hi-Res / 杜比）且带「切换后当前歌曲不中断，从下一首开始生效」说明；
> 歌单页单曲下载 → 图标由下载箭头变 **✓ 已下载**；
> 设置页「缓存管理」显示 **已用 10.9 MB / 上限 2.00 GB**，单曲条目「已下载 · 10.9 MB」+ 可删除 + 可清空；
> 小组件 Provider 已在系统中注册（`dumpsys appwidget` 可见 `PlayerWidgetReceiver`，resizeMode=horizontal|vertical）；
> **深色模式卡片对比度已修**：页面背景 RGB(5,6,10) / 卡片 RGB(31,34,43) / 导航栏 RGB(43,46,55)，层次清晰。
>
> **断网离线播放（最强证据）**：`svc wifi disable` + `svc data disable` 后
> `Active default network: none`、`Wi-Fi is disabled`，此时启动 App 播放**已下载**的第 1 首 →
> MediaSession `state=PLAYING, position` 正常推进，`dumpsys audio` 里 AudioTrack `state:started` 48kHz
> —— 完全无网络也能播，证明缓存 key 用 `bvid-cid-音质`（而不是 120 分钟就过期的 URL）是对的。
>
> **字体 1.3 倍 + 横屏**：把系统 `font_scale` 临时改成 1.3，设置页 UID、超长副标题、
> 缓存占用信息全部正常换行不裁切，卡片高度自适应；横屏下歌单网格、歌单详情、播放页（左右分栏）均不破版，
> 旋转不重建（`configChanges` 已声明）播放不中断。验证后已把 `font_scale` / `user_rotation` / `accelerometer_rotation` 恢复原值。

- **FR-9 离线缓存**：
  - `SimpleCache` + `LeastRecentlyUsedCacheEvictor`，缓存目录 `getExternalFilesDir("media")`（**不需要任何存储权限**）
  - **缓存 key 用 `bvid-cid-音质`，不用 URL**（URL 只有 120 分钟有效期）：
    播放侧走 `MediaItem.customCacheKey`、下载侧走 `DownloadRequest.customCacheKey`，两边同一个 key 才能互相命中
  - 命中缓存时**根本不会去解析取流地址**，所以断网也能播已下载的歌
  - 下载用 Media3 `DownloadManager`，线程池固定 2 个线程 = **并发上限 2**（避免风控）；
    下载状态同步进 Room，列表项显示 未下载 / 排队中（转圈）/ 下载中（进度环）/ ✓ 已下载 / 失败（红图标可重下）
  - 缓存管理：总占用 / 上限、逐条删除、清空缓存、上限可选 1/2/4/8 GB
- **FR-10 音质选择**：6 档单选 + 「切换后当前歌曲不中断，从下一首生效」说明；
  接口没有对应档位时自动降级，并在播放页弹一次 Snackbar 告知实际生效档位
- **FR-11 桌面小组件（Glance，4x2）**：显示曲名 / UP主 / 第几首 / 音质 / 播放模式，
  三个按钮**通过 MediaController 真的控制播放**（上一首 / 播放暂停 / 下一首）；未播放时显示「未在播放」，点整块打开 App
  - 踩过的坑：Glance 的 composition **不会常驻监听**，光在 `provideGlance` 里 collect 状态是不够的
    （桌面会一直停在首次渲染的画面）；必须在播放状态有实质变化时主动 `PlayerWidget().updateAll(context)`
    —— 位置跳动这种高频变化要排除，否则每秒都在刷 RemoteViews
- **适配打磨**：
  - 深色模式卡片层次（见上方实测数值）：把 `background` 压深、`surface` 略提亮 ——
    实测发现「动态取色下 background 与 surface 同色，且 Card 底色跟 surface 走」，只提亮 surface 会把整屏一起提亮
  - 歌单列表用 `LazyVerticalGrid(GridCells.Adaptive(160dp))`，平板/横屏自动多列
  - 歌单详情列表限宽 760dp，避免平板上每行拉满
  - 播放页横屏/平板自动切左右分栏；`configChanges` 已声明，旋转不重建、播放不中断


---

## 二、技术栈与版本

| 类别 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.10.01）+ Material 3 + Navigation Compose 2.8.4 |
| 播放内核 | androidx.media3 1.5.1（ExoPlayer + MediaSession + MediaSessionService） |
| 构建 | AGP 8.7.3 + Gradle 8.9 + Version Catalog |
| 依赖注入 | Hilt 2.52（KSP） |
| 本地存储 | Room 2.6.1（KSP）+ DataStore Preferences 1.1.1 |
| 网络 | OkHttp 4.12.0 + Retrofit 2.11.0 + kotlinx.serialization 1.7.3 |
| 图片 / 二维码 / 小组件 | Coil 2.7.0 / ZXing 3.5.3 / Glance 1.1.0 |
| 版本要求 | minSdk 26（Android 8.0）、compileSdk 35、targetSdk 35 |

---

## 三、下载安装（不想自己编译）

到 **[Releases](https://github.com/amns56780/bili-music/releases)** 下载最新的
`bilimusic-vX.Y.Z.apk`，传到手机点开安装即可。

- 需要 **Android 8.0（API 26）或更高**
- 用的是 **debug 签名**（本项目只做本地侧载、不上架），安装时系统会提示「未知来源应用」，允许即可
- 安装包很小（约 25 MB），不含任何第三方统计/广告 SDK
- 首次播放时才会申请通知权限；不申请存储权限（缓存写在 App 私有目录）

> ⚠️ 这是**非官方**的第三方客户端，使用 B 站非官方公开接口，仅供个人学习交流，**请勿用于商业用途**。
> 使用非官方接口存在账号被风控的风险，请自行评估。

---

## 四、如何构建

### 1. 环境要求

- **JDK 17 或 21**（推荐 21）。**不要用 JDK 25**：Gradle 8.9 尚不支持。
- **Android SDK**：需要 `platforms;android-35`、`build-tools;35.0.0`、`platform-tools`。
  - 在 `local.properties` 里写 `sdk.dir=<你的 SDK 路径>`（Windows 一般是 `%LOCALAPPDATA%\Android\Sdk`，macOS/Linux 见 Android Studio 设置）。
  - 换机器时改 `local.properties` 里的 `sdk.dir`，或用环境变量 `ANDROID_HOME`。**该文件已在 `.gitignore` 中，不要提交**。
  - 命令行安装：`sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`

### 2. 构建命令

```powershell
# Windows（PowerShell）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
cd BiliMusic
.\gradlew.bat assembleDebug
```

```bash
# macOS / Linux
cd BiliMusic
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug
```

**产物路径**：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk   # 需先 .\gradlew.bat assembleRelease
```

### 3. 关于 Gradle 下载源（重要）

`gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` **指向腾讯云镜像**：

```
https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
```

原因：本机访问官方源 `https://services.gradle.org/...` 会卡在 307 跳转（实测 20 秒无响应），镜像 0.1 秒可下。
如果你的网络能直连官方源，把它改回下面这行即可：

```
https://services.gradle.org/distributions/gradle-8.9-bin.zip
```

> 注意：`distributionUrl` 里的 `:` 需要写成 `\:`（java properties 转义）。

### 4. 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者把 APK 拷到手机点击安装（需允许「安装未知来源应用」）。

> release 包目前直接复用 debug 签名（本项目只做本地侧载，不上架），所以 debug/release 包可以互相覆盖安装。

---

## 四、目录结构

```
app/src/main/java/com/bilimusic/app/
├── data/
│   ├── local/            # Room 实体 / DAO / 数据库 / DataStore 偏好
│   ├── remote/           # Retrofit 接口、DTO、WBI 签名、OkHttp 拦截器（Phase 2）
│   ├── repository/       # 唯一对上层暴露数据的入口
│   └── player/           # PlaybackService、PlayerHolder、CacheKeyFactory（Phase 4+）
├── di/                   # Hilt 模块
├── domain/model/         # 领域模型（Playlist / Song / Episode / SongDraft）
├── ui/
│   ├── theme/            # Material 3 主题、动态取色、字体
│   ├── navigation/       # 路由表 + NavHost + 底部导航
│   ├── playlist/         # 歌单列表 / 歌单详情
│   ├── components/       # 空状态、加载态、错误态、对话框等公共组件
│   └── settings/         # 设置页
├── widget/               # Glance 桌面小组件（Phase 7）
├── MainActivity.kt       # 单 Activity 入口
└── BiliMusicApplication.kt
```

**架构纪律**：UI 只跟 ViewModel 交互，ViewModel 只跟 Repository 交互，Repository 才碰网络和数据库；
播放器的唯一真源是 `MediaSessionService` 里的 `ExoPlayer` 实例，UI 通过 `MediaController` 连接。

---

## 五、功能说明与验收清单（FR-1 ~ FR-11）

| 编号 | 功能 | 说明 | 状态 | 真机证据（Android 16 真机） |
| --- | --- | --- | --- | --- |
| FR-1 | 登录 B 站 | 扫码登录（180 秒有效期、倒计时、4 种状态文案、2 秒轮询 + 退避）+ Cookie 手动粘贴兜底；登录态加密持久化 | ✅ | 二维码扫码登录成功（真实账号）；手动 Cookie 登录成功；杀进程重开仍保持登录；退出登录回到未登录态 |
| FR-2 | 歌单导入 | 收藏夹 / 稍后再看 / UP主投稿 / 合集 4 种来源 + 手动新建；分页拉全、失效视频跳过并出报告 | ✅ | 收藏夹导入 5 首、稍后再看导入 99 首、UP主投稿导入 6 首；合集条目识别为「第 725 集 / 共 850 集」；粘贴 BV 号加单曲成功且重复自动跳过 |
| FR-3 | 选集 | 分P 与合集都能逐条勾选，默认全选，全选/全不选/反选，`已选 n / 共 m`，勾选持久记忆，队列只含勾选项且保持原始顺序 | ✅ | 真实 639 集合集：默认「已选 639 / 共 639」；反选→「已选 0」+ 按钮置灰 +「请至少选择 1 集」；退出重进勾选保留；先勾第 3 集再勾第 1 集 → 播放页「第 1 首 / 共 2 首」且从第 1 集播；队列页显示「合集 · 第 1 集 / 共 639 集」 |
| FR-4 | 播放模式 + 顺序可视化 | 顺序 / 列表循环 / 单曲循环 / 随机（固定 seed）；播放页常驻「第 X 首 / 共 N 首」；队列页按真实播放顺序编号、当前项高亮 | ✅ | 四种模式循环切换；播放页显示「第 1 首 / 共 5 首」；队列页按真实顺序编号 1..N、当前项高亮 + 呼吸电平动画；点第 3 项成功跳到第 3 首（`active item id=2`） |
| FR-5 | 定时关闭 | 15/30/45/60/90/120 分钟 + 自定义；默认「播完当前歌曲后停止」；`elapsedRealtime` 计时；到点后当前曲完整播完才停 | ✅ | 自动化用例 `SleepTimerDeadlineTest` 通过：到点后标签变「本曲播完后停止」、**音乐不中断**（60 秒等待期间位置持续推进），本曲自然播完后**停止且不多播下一首**；未选定时间时点「开始」被拦截 |
| FR-6 | 后台不被其他 App 打断 | `setAudioAttributes(..., handleAudioFocus = false)`，不申请音频焦点，两路声音同时出声 | ✅ | `dumpsys audio` 焦点栈里**没有本 App**；与抖音（uid 10326）**同时** `state:started`（我方 48kHz、抖音 44.1kHz）、两边 `mutedState:none`；12 秒内我方播放位置推进 12012ms，无暂停无降音量 |
| FR-7 | UI 规范 | Material 3、动态取色、深色模式、edge-to-edge、字体 1.3x、横屏/平板分栏；每个按钮都有反馈，空状态/加载/错误三态齐全 | ✅ | 全中文界面；字体 1.3x 下无裁切（UID、长副标题、缓存信息均正常换行）；横屏歌单网格 / 详情 / 播放页左右分栏均不破版、旋转不重建；深色卡片层次 RGB(5,6,10) 底 / RGB(31,34,43) 卡片 |
| FR-8 | 通知栏 / 锁屏 / 线控 | MediaSessionService 默认通知 + 锁屏控制 + 蓝牙线控 + 定时剩余时间辅助行 | ✅ | 通知栏媒体卡片（封面/标题/UP主 + 上一首/暂停/下一首，`category=transport actions=3 vis=PUBLIC`）；`KEYCODE_MEDIA_PLAY_PAUSE` 暂停→播放、`KEYCODE_MEDIA_NEXT` 切歌；设了定时后通知栏带剩余时间 |
| FR-9 | 离线缓存 | SimpleCache + DownloadManager；cacheKey = `bvid-cid-音质`（不能用 120 分钟过期的 URL 当 key）；LRU 淘汰 | ✅ | 单曲下载后图标变 ✓ 已下载，缓存管理显示「已用 10.9 MB / 上限 2.00 GB」「已下载 · 10.9 MB」；**`svc wifi/data disable` 完全断网**（`Active default network: none`）后仍能正常播放已下载曲目（PLAYING + AudioTrack `state:started` 48kHz） |
| FR-10 | 音质选择 | 自动 / 192K / 132K / 64K / Hi-Res / 杜比；自动档永不失败，不支持时自动降级到最接近的一档 | ✅ | 设置页 6 档单选面板齐全 + 文案「切换后当前歌曲不中断，从下一首开始生效」；播放页显示实际生效档位（192K）；接口无对应档位时弹 Snackbar 告知降级结果 |
| FR-11 | 桌面小组件 | Glance 4x2，封面 + 标题 + 播放/暂停/上下曲（真的能控制播放） | ✅ | **桌面实测**：小组件显示「曲名 / UP主 / 第 2 首 / 共 5 首 · 192K」，点「暂停」→ `state=PAUSED speed=0.0`，点「下一首」→ `active item id=1` 且小组件刷新为新曲名，点「播放」→ 恢复 PLAYING，点卡片主体→打开 App；另有渲染测试（3 个，Glance 官方单测框架）与按钮功能测试（`WidgetControlTest`） |

> 状态说明：✅ = 代码完成且真机验证通过；🟡 = 代码完成，还差最后一步人工确认。


---

## 六、已知限制（务必先读）

### 关于「后台播放不被其他 App 打断」（FR-6）

本 App **刻意不申请音频焦点**（`handleAudioFocus = false`），这样才能做到「抖音/微信在放声音时，本 App 的音乐不降音量、不暂停，两路同时出声」。代价是：

1. **来电、系统闹钟、语音助手占用音频通道属于系统/硬件层抢占，无法完全避免被静音。**
2. **部分国产 ROM（MIUI / ColorOS / HarmonyOS 等）有自己的后台播放管控**，可能强制暂停或压低后台音量。App 内提供「引导关闭电池优化」的设置项（Phase 5）。
3. **不申请音频焦点的副作用**：个别机型上系统音量面板可能不把本 App 识别为「活跃媒体会话」。这属于预期取舍，不是 Bug。
4. 同理，**拔耳机不会自动暂停**（`setHandleAudioBecomingNoisy(false)`）。

### 其他限制

- 使用 B 站**非官方接口**，接口随时可能变更；接口变动导致的功能失效不属于 Bug。
- **UP 主投稿列表接口风控最严**，持续返回 `-352` / `-412` 时会降级为友好提示并置灰，不硬刚。
- 取流 URL **只有 120 分钟有效期**，过期后需要重新取流（已做自动重取）。
- App 内所有文案为简体中文硬编码在 Compose 里（单语言自用项目，未做多语言资源）。

---

## 七、免责声明与开源协议

1. 本项目使用的 B 站接口均为**非官方公开接口**，可能随时变更或失效；接口变动导致的功能失效不属于 Bug，需要后续适配。
2. 本项目**仅供个人学习交流使用**（源码以 MIT 协议开源），**禁止任何商业用途、禁止用于盈利场景**。
3. 使用非官方接口存在**账号被风控**的风险，风险由使用者自负。
4. 通过本 App 播放的所有音频内容的**版权归原 UP 主与哔哩哔哩所有**，本 App 不存储、不转码、不分发任何内容，仅做个人播放用途。
5. 本项目与哔哩哔哩（Bilibili）官方**没有任何关系**，未获得其授权或认可；项目名称与图标均为自绘/自拟，未使用官方商标素材。
6. 如有侵权，请联系删除。

### 开源协议

代码以 [MIT License](LICENSE) 发布。你可以自由使用、修改、分发，但需保留版权声明。

> 附加说明（非协议条款，仅为请求）：本项目与哔哩哔哩（Bilibili）官方无任何关系，
> 使用其非官方公开接口，**仅供个人学习交流，请勿用于任何商业或盈利场景**；
> 通过本项目播放的音频内容版权归原 UP 主与哔哩哔哩所有。

---

## 九、参与贡献

想提 Issue 或 PR？请先看 **[CONTRIBUTING.md](CONTRIBUTING.md)**（环境要求、代码约定、
提交前必过的检查、以及**绝对不能提交的东西**）。

- 🐞 [反馈 Bug](https://github.com/amns56780/bili-music/issues/new?template=bug_report.yml) —— 模板会提醒你脱敏日志
- 💡 [提功能建议](https://github.com/amns56780/bili-music/issues/new?template=feature_request.yml)
- 📦 [下载最新 APK](https://github.com/amns56780/bili-music/releases/latest)

> 提交前请务必确认：**没有把 `local.properties`、keystore、真实 SESSDATA / Cookie 或抓包取流 URL 提交上去。**

