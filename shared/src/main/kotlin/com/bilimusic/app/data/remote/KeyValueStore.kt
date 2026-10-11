package com.bilimusic.app.data.remote

/**
 * 键值存储抽象。
 *
 * 共享层需要一个能落盘的地方（Cookie、账号快照），但不该依赖任何平台实现：
 * - 安卓端：由 `SecurePrefs`（EncryptedSharedPreferences）实现
 * - 桌面端：由本地文件实现（用户目录下的 JSON）
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
