# bilimusic · Windows 桌面版

B 站音源的 Windows 音乐播放器，与安卓版**共用同一套后端逻辑**（`shared` 模块）。
用 Jetpack Compose（Multiplatform 桌面版）做界面，**VLC** 作为播放内核。

---

## 一、功能一览

| 功能 | 说明 |
| --- | --- |
| 登录 | 扫码登录（二维码 + 轮询）/ 粘贴整段 Cookie 或只粘 SESSDATA |
| 在线播放 | WBI 签名取流、6 档音质选择、4 种播放模式（顺序 / 列表循环 / 单曲循环 / 随机）、队列可视化 |
| 歌单导入 | 收藏夹 / 稍后再看 / **合集或系列**（粘贴合集链接或 ID，接口自带 cid 可秒导）；UP主投稿接口已就绪，界面待接 |
| 歌单管理 | 新建 / 重命名 / 删除 / 排序；复制到歌单、移动到歌单（去重） |
| 本地音乐 | 扫描「音乐 / 下载 / 桌面」→ 直接播放 / 导入歌单（与在线曲目混排） |
| 定时关闭 | 15/30/60/90 分钟，到点**等当前曲播完再停**（不写盘，不存在"幽灵定时"） |
| 系统托盘 | 关窗口只收进托盘继续播放；托盘菜单可控制播放/切歌/退出 |
| 下载离线缓存 | 下载当前曲目到 `~/.bilimusic/downloads/`，之后**离线优先播放** |

数据目录：`~/.bilimusic/`
- `settings.json` —— Cookie / 音质等偏好（⚠️ 明文存储，安卓端是加密的）
- `playlists.json` —— 歌单与曲目（写入用「临时文件 + 改名」，写坏不了）
- `downloads/` —— 已下载音频 + `downloads.json` 索引

---

## 二、运行 / 打包

> 前置：**JDK 17+**（本项目用 JDK 21 验证）、**已安装 VLC**（<https://www.videolan.org>，播放内核依赖它）。

```powershell
# 1) 直接运行（开发用）
.\gradlew.bat :desktop:run

# 2) 免安装绿色版（不需要额外工具链）
#    产物：desktop\build\compose\binaries\main\app\bilimusic\bilimusic.exe
.\gradlew.bat :desktop:createDistributable

# 3) 安装包（需要先安装 WiX Toolset v3：https://wixtoolset.org）
.\gradlew.bat :desktop:packageMsi     # 产出 .msi
.\gradlew.bat :desktop:packageExe     # 产出 .exe
```

没装 VLC 时程序不会崩溃：会停在提示页，提示安装 VLC 后重启。

---

## 三、工程结构

```
BiliMusic/
├── shared/    纯 JVM 共享层（安卓与桌面共用，无 Android 依赖）
│   ├── data/remote/   WBI 签名、BiliApi、DTO、Cookie 存储（KeyValueStore 抽象）
│   ├── data/player/   取流（StreamUrlRepository）、音质档位提供者
│   ├── domain/model/  歌单 / 歌曲 / 音质 / 播放模式 / 账号等模型
│   └── util/          跨平台日志（AppLog）、单调时钟、B 站链接解析
├── desktop/   Windows 端（Compose Multiplatform + VLCJ）
│   ├── data/          本地设置、歌单存储、导入、本地音乐扫描、下载服务
│   ├── player/        VLC 封装、播放控制（队列/模式）、定时关闭
│   ├── media/         粘贴 BV/链接直接播放
│   └── ui/            登录页、主界面、歌单栏、播放器面板、队列、本地音乐对话框
└── app/       安卓端（与桌面端共用 shared）
```

**为什么取流要共用**：B 站音频地址需要 WBI 签名，且 `bilivideo`/`hdslb` 域名必须带
`Referer` + `User-Agent`，否则一律 403。桌面端把这些头通过 libvlc 的全局选项传给 VLC
（`VlcPlayer`），安卓端由 OkHttp 拦截器注入 —— 逻辑同源，避免"安卓能播、桌面 403"。

---

## 四、已知限制

- **Cookie 明文存储**：桌面端为省依赖没有引入 DPAPI 之类的加密；文件在用户目录下，默认仅本用户可读写。
- **真·全局媒体键**：目前支持窗口内 `Space` / 媒体播放键；系统级全局媒体键需要引入 JNativeHook（它也要解包原生库），未纳入当前版本。
- **不解析音频标签**：本地音乐标题取文件名（不读 ID3），艺术家取所在文件夹名。
- **UP主投稿导入**：接口与 DTO 已在 `shared` 中（`spaceArcSearch`），界面待接（合集导入已完成）。
- **歌单多选批量复制/移动**：存储层已支持（`PlaylistStore.copySongsTo` / `moveSongsTo`），界面待接。
