package com.bilimusic.app.data.remote

import com.bilimusic.app.util.AppLog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录 Cookie 仓库（任务书 FR-1）：
 * - 保存 SESSDATA / bili_jct / DedeUserID / DedeUserID__ckMd5 / sid，顺带保存 buvid 等风控相关 Cookie
 * - 落盘通过 [KeyValueStore]（安卓端=加密偏好，桌面端=本地文件），内存里保留一份供 OkHttp 同步读取
 * - 支持从「整段 Cookie 字符串」解析（手动粘贴保底路径）
 *
 * 线程安全：OkHttp 的 CookieJar 会在多个网络线程并发调用。
 */
@Singleton
class CookieStore @Inject constructor(
    private val storage: KeyValueStore,
    private val json: Json,
) {

    private val lock = Any()
    private val cookies = LinkedHashMap<String, String>()

    init {
        val raw = storage.getString(KEY_COOKIE_BLOB)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val map = json.decodeFromString<Map<String, String>>(raw)
                cookies.putAll(map)
            }.onFailure { AppLog.w(TAG, "读取本地 Cookie 失败，按未登录处理", it) }
        }
    }

    // ---------------- 读取 ----------------

    /** 拼成 Cookie 请求头；没有 Cookie 时返回 null */
    fun header(): String? = synchronized(lock) {
        if (cookies.isEmpty()) {
            null
        } else {
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    fun value(name: String): String? = synchronized(lock) { cookies[name] }

    fun pairs(): List<Pair<String, String>> = synchronized(lock) { cookies.map { it.key to it.value } }

    val hasSessData: Boolean
        get() = !value(KEY_SESSDATA).isNullOrBlank()

    val sessData: String? get() = value(KEY_SESSDATA)

    val biliJct: String? get() = value(KEY_BILI_JCT)

    val dedeUserId: String? get() = value(KEY_DEDE_USER_ID)

    /** 写操作要带的 csrf 参数 */
    val csrf: String? get() = value(KEY_BILI_JCT)

    // ---------------- 写入 ----------------

    fun putAll(values: Map<String, String>) {
        val cleaned = values.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        if (cleaned.isEmpty()) return
        synchronized(lock) {
            cookies.putAll(cleaned)
            persistLocked()
        }
    }

    /** 手动粘贴场景：整体替换，避免旧账号的 Cookie 残留 */
    fun replaceAll(values: Map<String, String>) {
        synchronized(lock) {
            cookies.clear()
            cookies.putAll(values.filter { it.key.isNotBlank() && it.value.isNotBlank() })
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
            storage.remove(KEY_COOKIE_BLOB)
        }
    }

    private fun persistLocked() {
        storage.putString(KEY_COOKIE_BLOB, json.encodeToString(cookies.toMap()))
    }

    // ---------------- 解析 ----------------

    /**
     * 解析用户粘贴的内容，两种情况都支持：
     * 1. 整段 Cookie：`SESSDATA=xxx; bili_jct=yyy; DedeUserID=123`
     * 2. 只有 SESSDATA 的值：`xxxx%2Cyyyy`
     */
    fun parseRawCookieInput(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyMap()

        val map = LinkedHashMap<String, String>()
        // 统一分隔符：换行/tab → 分号
        val normalized = text.replace('\n', ';').replace('\r', ';').replace('\t', ';')

        if (normalized.contains('=')) {
            normalized.split(';').forEach { segment ->
                val part = segment.trim()
                if (part.isEmpty()) return@forEach
                val idx = part.indexOf('=')
                if (idx <= 0) return@forEach
                val name = part.substring(0, idx).trim().removePrefix("Cookie:").trim()
                val value = part.substring(idx + 1).trim().trim('"')
                if (name.isNotEmpty() && value.isNotEmpty()) map[name] = value
            }
        }

        // 整段里没解析出 SESSDATA，但用户可能只粘了值
        if (!map.containsKey(KEY_SESSDATA)) {
            val onlyValue = text.trim().trim('"')
            if (!onlyValue.contains('=') && onlyValue.length > 8 && !onlyValue.contains(' ')) {
                map[KEY_SESSDATA] = onlyValue
            }
        }
        return map
    }

    /**
     * 扫码登录成功时，接口返回的 url 的 query 里带着登录 Cookie，
     * 兜底解析一遍（正常情况下 Set-Cookie 已经被 CookieJar 接住了）。
     */
    fun mergeFromLoginUrl(url: String): Int {
        if (url.isBlank()) return 0
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return 0
        val map = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val name = pair.substring(0, idx)
            if (name in WANTED_COOKIE_KEYS) {
                val value = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }
                    .getOrDefault(pair.substring(idx + 1))
                if (value.isNotBlank()) map[name] = value
            }
        }
        putAll(map)
        return map.size
    }

    companion object {
        private const val TAG = "CookieStore"
        private const val KEY_COOKIE_BLOB = "cookie_blob"

        const val KEY_SESSDATA = "SESSDATA"
        const val KEY_BILI_JCT = "bili_jct"
        const val KEY_DEDE_USER_ID = "DedeUserID"
        const val KEY_DEDE_USER_ID_CK_MD5 = "DedeUserID__ckMd5"
        const val KEY_SID = "sid"

        /** 任务书 FR-1 要求持久化的 5 个字段（其余 Cookie 一并保留，利于风控通过） */
        val WANTED_COOKIE_KEYS = setOf(
            KEY_SESSDATA,
            KEY_BILI_JCT,
            KEY_DEDE_USER_ID,
            KEY_DEDE_USER_ID_CK_MD5,
            KEY_SID,
        )
    }
}
