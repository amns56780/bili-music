pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BiliMusic"
include(":app")
// 纯 JVM 共享层：安卓端与 Windows 桌面端共用 WBI 签名 / 接口 / 领域模型
include(":shared")
// Windows 桌面端（Compose Multiplatform + VLC）
include(":desktop")
