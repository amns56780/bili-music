# 贡献指南（CONTRIBUTING）

感谢愿意帮这个项目做点事 🙌 这是一个**个人自用向**的 B 站音源播放器，代码以 MIT 开源，
欢迎 Issue 与 PR，但请先读完下面几条——能省掉很多来回。

> ⚠️ **本项目与哔哩哔哩官方无关**，使用的是非官方公开接口。请不要提交任何「绕过风控 / 破解会员 /
> 下载付费内容」相关的代码或需求，这类 PR 会直接关闭。

---

## 一、开发环境

| 项 | 版本 |
| --- | --- |
| JDK | **17 或 21**（推荐 21，**不要用 25**，Gradle 8.9 不支持） |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0`、`platform-tools` |
| Gradle | 用仓库自带的 wrapper（8.9，腾讯镜像），**不要**用本机 Gradle |
| IDE | Android Studio Ladybug 或更新（需要 Kotlin 2.0 支持） |

```bash
# 1. 克隆
git clone https://github.com/amns56780/bili-music-android.git
cd bili-music-android

# 2. 配置 SDK 路径（该文件已被 .gitignore 排除，不要提交）
echo "sdk.dir=$LOCALAPPDATA/Android/Sdk" > local.properties   # Windows
# echo "sdk.dir=$HOME/Android/Sdk" > local.properties         # macOS/Linux

# 3. 构建
./gradlew assembleDebug              # 出 APK：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest          # 单元测试（24 个，必须全绿）
./gradlew assembleDebugAndroidTest   # 插桩测试 APK

# 4. 真机插桩测试（需要一台连着 adb 的设备）
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.bilimusic.app.WidgetControlTest \
  com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
```

> 部分国产 ROM 跑插桩测试时会弹「允许 XX 打开测试包」的确认框，点允许即可；
> 测试结束后被测进程被回收属于正常现象。

---

## 二、提交前必须过的检查

1. `./gradlew assembleDebug` **零错误**（警告尽量清零）
2. `./gradlew testDebugUnitTest` **全绿**
3. **不要引入** `TODO()` / `FIXME` / 空实现 / `onClick = {}`
   —— 本项目的规矩是：**每个按钮都必须真的能用**，做不了就置灰 + 给原因，不要留空壳
4. 新增功能请在 README 的 FR 表里补一行（功能 / 说明 / 状态 / 证据）
5. 改了 UI 请附**真机截图**；改了播放链路请附 `dumpsys media_session` 或 `dumpsys audio` 的关键输出

自查命令：

```bash
# 禁用项扫描（应当没有任何输出）
grep -rnE "TODO\(|FIXME|onClick\s*=\s*\{\s*\}" app/src --include=*.kt --include=*.xml
```

---

## 三、代码约定

- **语言**：Kotlin，跟随官方代码风格（4 空格缩进，行宽 120）
- **架构**：MVVM + Repository，UI 只碰 ViewModel，网络/数据库只在 data 层
- **UI**：Jetpack Compose + Material 3，**界面文案统一简体中文**
- **依赖注入**：Hilt；新仓库记得在 `di/RepositoryModule.kt` 里 `@Binds`
- **状态**：`StateFlow` + `collectAsStateWithLifecycle()`；不要用 `LiveData`
- **异步**：协程，禁止在主线程做 IO；网络请求统一走 `ApiCall`（自带重试与退避）
- **注释**：关键坑点请写**为什么**这么写（例如「为什么这里不能 stopSelf()」），不要写「做了什么」

### 目录速查

```
app/src/main/java/com/bilimusic/app/
├── data/
│   ├── local/          Room（entity / dao / prefs）
│   ├── remote/         BiliApi、WBI 签名、Cookie 处理、错误映射
│   ├── player/         ExoPlayer 服务、取流、缓存、定时关闭、通知
│   └── repository/     仓库层（业务逻辑都在这）
├── domain/model/       领域模型（不依赖 Android）
├── di/                 Hilt 模块
├── ui/                 Compose 界面（按页面分包）
└── widget/             Glance 桌面小组件
```

---

## 四、绝对不要提交的东西

- ❌ `local.properties`（含本机 SDK 路径，已在 `.gitignore`）
- ❌ 任何 `*.jks` / `*.keystore` / `keystore.properties`
- ❌ **真实的 `SESSDATA` / Cookie / 账号 UID / 手机号**（贴 Issue 时也要打码）
- ❌ 抓包得到的真实取流 URL（**120 分钟就过期**，贴了也没用，还可能带 `deadline` 签名）
- ❌ `build/`、`.gradle/`、`.kotlin/`、`.idea/`、APK 产物

> 提交前建议跑一遍：`git status` 看清每个将要提交的文件；`git diff --cached` 看内容。

---

## 五、Issue 怎么写才有人回

- **Bug**：机型 + Android 版本 + 复现步骤 + 期望/实际 + 日志（`adb logcat -s BiliMusic:*`）
- **功能请求**：说清**使用场景**，不要只说「加个 XX」；如果是 B 站接口层面的能力，请附接口名
- 一个 Issue 只说一件事，别把三个 bug 塞一条

## 六、PR 流程

1. Fork → 从 `main` 切分支：`fix/xxx` 或 `feat/xxx`
2. 提交信息用中文可以，但请写清**改了什么、为什么**
3. PR 描述里勾选自检项（模板会自动带出来）
4. 一个 PR 只解决一个问题；大改动请先开 Issue 讨论，避免白写
5. 合并后我会尽快打新 Release

---

## 七、已知不做的事

- 不做登录态之外的账号体系、不做云同步
- 不申请音频焦点是**默认设计**（要跟其他 App 同时出声）；它现在是设置页「与其他 App 同时播放」开关的默认值，
  不是硬编码行为——改这个开关时别把默认值改掉，也别在服务里自己调 AudioManager.requestAudioFocus()（会跟 ExoPlayer 内部冲突）
- 不内置任何音源代理服务；只直连 B 站公开接口
