package com.bilimusic.desktop.data

import com.bilimusic.app.data.remote.KeyValueStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 桌面端的键值存储：用户目录下的一个 JSON 文件（`~/.bilimusic/settings.json`）。
 *
 * ⚠️ 与安卓端不同：安卓用 EncryptedSharedPreferences 加密保存登录 Cookie，
 * 桌面这里为了不引入额外依赖，是**明文**存的（文件位于当前用户目录，默认只有本用户可读写）。
 * 介意的话可以在 Windows 上改用 DPAPI 加密，属于后续可选项。
 */
class DesktopStorage(
    private val file: File = defaultFile(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) : KeyValueStore {

    private val lock = Any()
    private val values: MutableMap<String, String> = readFromDisk()

    override fun getString(key: String): String? = synchronized(lock) { values[key] }

    override fun putString(key: String, value: String) {
        synchronized(lock) {
            values[key] = value
            flushLocked()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            values.remove(key)
            flushLocked()
        }
    }

    /** 数据文件所在目录，设置页里可以直接「打开所在文件夹」 */
    val directory: File get() = file.parentFile

    private fun readFromDisk(): MutableMap<String, String> = runCatching {
        if (!file.exists()) return@runCatching mutableMapOf()
        json.decodeFromString<Map<String, String>>(file.readText())
            .toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun flushLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString<Map<String, String>>(values.toMap()))
        }
    }

    companion object {
        fun defaultDir(): File = File(System.getProperty("user.home"), ".bilimusic")

        fun defaultFile(): File = File(defaultDir(), "settings.json")
    }
}
