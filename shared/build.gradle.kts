import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // 纯 JVM 共享层：WBI 签名、接口 DTO/API、领域模型、音质与链接解析
    // Android 端与 Windows 桌面端都依赖这里，逻辑只写一份
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.retrofit.kotlinx.serialization)

    testImplementation(libs.junit)
}
