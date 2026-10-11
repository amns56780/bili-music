package com.bilimusic.app.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 加密偏好存储（登录 Cookie、账号快照都放这里）。
 *
 * 任务书要求用 AndroidX EncryptedSharedPreferences 加密存储 SESSDATA 等敏感字段。
 * 少数 ROM 上 Keystore 可能不可用，这时降级为普通 SharedPreferences 并打日志，
 * **不让 App 因为加密组件初始化失败而无法登录**（降级事实会写在 README 里）。
 */
@Singleton
class SecurePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) : com.bilimusic.app.data.remote.KeyValueStore {

    val preferences: SharedPreferences = createPreferences(context)

    /** true 表示加密存储初始化失败、已降级为明文（仅用于调试与 README 说明） */
    val isFallbackPlain: Boolean = preferences !is EncryptedSharedPreferences

    // ---------------- 共享层（shared 模块）用的键值接口 ----------------
    // CookieStore 等逻辑在 shared 里，只依赖这个接口，不依赖 Android 的 SharedPreferences

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun createPreferences(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        Log.w(TAG, "EncryptedSharedPreferences 初始化失败，降级为普通 SharedPreferences", t)
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "SecurePrefs"
        private const val FILE_NAME = "bilimusic_secure_prefs"
        private const val FALLBACK_FILE_NAME = "bilimusic_plain_prefs"
    }
}
