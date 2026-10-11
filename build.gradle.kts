plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // Compose Multiplatform（桌面端）：必须在根项目声明，否则子模块之间会出现
    // "plugin is already on the classpath with an unknown version" 的版本判定失败
    alias(libs.plugins.compose.multiplatform) apply false
}
