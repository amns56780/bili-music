import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Dispatchers.Main 在桌面端由 Swing EDT 承载
    implementation(libs.kotlinx.coroutines.swing)
    // 扫码登录要把二维码画出来（纯 JVM，无需 Android）
    implementation(libs.zxing.core)
    // 播放内核：VLC（兼容性最好，B站返回的 m4a/aac/flac 都能放）
    implementation(libs.vlcj)
    // 系统级全局媒体键：直接用 JNA 调 Win32 RegisterHotKey（vlcj 已带 JNA，这里显式声明便于编译期使用）
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.bilimusic.desktop.MainKt"

        // 受限环境（沙箱 / CI）里系统临时目录可能不可用，skiko 解包原生库会失败。
        // 用机器级 Gradle 属性把临时目录指到可写位置；不设置时行为与默认一致。
        providers.gradleProperty("bilimusic.tmpDir").orNull?.let { tmp ->
            jvmArgs += "-Djava.io.tmpdir=$tmp"
        }
        // 更彻底的办法：直接指定 skiko 原生库（skiko.library.path），完全跳过解包
        providers.gradleProperty("bilimusic.skikoLibraryPath").orNull?.let { lib ->
            jvmArgs += "-Dskiko.library.path=$lib"
        }
        // ★ VlcJ 通过 JNA 把字符串传给 libvlc，JNA 默认用平台编码（中文系统是 GBK），
        //   而 libvlc 只认 UTF-8 —— 不设成 UTF-8 的话「中文文件名」的本地音乐全部播不了。
        jvmArgs += "-Djna.encoding=UTF-8"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "bilimusic"
            packageVersion = "1.1.2"
            description = "B站音源桌面音乐播放器"
            vendor = "amns56780"
            copyright = "MIT License"
            windows {
                menu = true
                shortcut = true
                // 目录名固定，保证覆盖安装能正确升级
                dirChooser = true
                // 固定 upgradeUuid：不固定的话每次构建都会变成"另一个应用"，无法覆盖升级
                upgradeUuid = "8f3c1b52-6d47-4a1e-9c88-2b0f5e7a4d31"
            }
        }
    }
}

// ---- 便携版（不依赖 jpackage / WiX）----
// jpackage 在受限环境里会因写入被拒而失败；这里用 Gradle 自己把运行时依赖 + 启动脚本
// 打成一个 zip，解压后双击 bilimusic.bat 即可运行（需要机器上有 JRE 17+）。
val portableRoot = layout.buildDirectory.dir("portable/bilimusic")

val portableDist by tasks.registering(Sync::class) {
    into(portableRoot)
    from(configurations.runtimeClasspath.get()) { into("lib") }
    from(tasks.named("jar")) { into("lib") }
}

val portableLauncher by tasks.registering {
    dependsOn(portableDist)
    outputs.upToDateWhen { false }
    doLast {
        val dir = portableRoot.get().asFile
        dir.mkdirs()
        File(dir, "bilimusic.bat").writeText(
            "@echo off\r\n" +
                "cd /d %~dp0\r\n" +
                "start \"bilimusic\" javaw -Dfile.encoding=UTF-8 -cp \"lib/*\" com.bilimusic.desktop.MainKt\r\n",
        )
        File(dir, "使用说明.txt").writeText(
            """
            bilimusic 桌面版（便携版）
            ========================

            运行前请确认：
              1) 已安装 JRE / JDK 17 或更高版本（java 在 PATH 里）
              2) 已安装 VLC（https://www.videolan.org）—— 播放内核依赖它

            启动：双击 bilimusic.bat

            数据目录：%USERPROFILE%\.bilimusic
              settings.json   登录 Cookie 与偏好（⚠️ 明文保存，注意保管）
              playlists.json  歌单与曲目
              downloads/      已下载的离线音频

            想要「安装包」（.msi/.exe）而不是便携版：
              gradlew :desktop:packageMsi   （需要先安装 WiX Toolset v3）
            """.trimIndent(),
        )
    }
}

tasks.register<Zip>("portableZip") {
    dependsOn(portableLauncher)
    from(portableRoot)
    archiveFileName.set("bilimusic-desktop-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
