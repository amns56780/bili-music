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

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "bilimusic"
            packageVersion = "1.0.0"
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
