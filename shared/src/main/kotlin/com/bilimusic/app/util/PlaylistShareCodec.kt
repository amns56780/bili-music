package com.bilimusic.app.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 分享链接里承载的数据。
 *
 * 只放歌单标题和 bvid 列表，不放标题/封面/时长 ——
 * 导入端拿到 bvid 后再逐条取视频信息（两端本来就有这套逻辑）。
 * 这样一首歌只占 12 字节，20 首的歌单链接大约 300 字符，可以直接发微信。
 */
@Serializable
data class SharedPlaylist(
    /** 协议版本，以后加字段时用来兼容旧链接 */
    val v: Int = 1,
    /** 歌单标题 */
    val t: String = "",
    /** 曲目 bvid 列表（保持原顺序） */
    val s: List<String> = emptyList(),
)

/**
 * 歌单分享链接的编解码（桌面端 / 安卓端共用）。
 *
 * 格式： bm://pl/v1/<base64url(deflate(JSON))>
 *
 * 不需要任何服务器：歌单数据全部编码在链接里，
 * 另一台设备粘贴链接 → 解码 → 逐首取信息 → 落库，就完成了「导入整个歌单」。
 */
object PlaylistShareCodec {

    const val PREFIX = "bm://pl/v1/"

    /** 单次分享的曲目上限，防止链接超长 */
    const val MAX_SONGS = 300

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun isShareLink(text: String): Boolean = text.trim().startsWith(PREFIX)

    /** 歌单标题 + 曲目 → 分享链接 */
    fun encode(title: String, bvids: List<String>): String {
        val payload = SharedPlaylist(
            t = title,
            s = bvids.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_SONGS),
        )
        val raw = json.encodeToString(SharedPlaylist.serializer(), payload).toByteArray(Charsets.UTF_8)
        return PREFIX + base64UrlEncode(deflate(raw))
    }

    /** 分享链接 → 歌单数据；不是分享链接或解码失败返回 null */
    fun decode(text: String): SharedPlaylist? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return runCatching {
            val body = trimmed.removePrefix(PREFIX)
            val inflated = inflate(base64UrlDecode(body))
            json.decodeFromString(SharedPlaylist.serializer(), String(inflated, Charsets.UTF_8))
                .takeIf { it.s.isNotEmpty() }
        }.getOrNull()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2 + 64)
            val buf = ByteArray(1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun base64UrlEncode(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun base64UrlDecode(text: String): ByteArray =
        Base64.getUrlDecoder().decode(text)
}