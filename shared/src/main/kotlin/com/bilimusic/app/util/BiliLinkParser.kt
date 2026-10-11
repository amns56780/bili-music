package com.bilimusic.app.util

/**
 * 解析用户粘贴的内容：BV 号 / AV 号 / 分享链接 / 空间链接 / 短链。
 * 纯逻辑，可单元测试。
 */
object BiliLinkParser {

    /** BV 号：BV + 10 位（大小写字母数字） */
    private val BV_REGEX = Regex("""BV[0-9A-Za-z]{10}""")

    /** av 号：av123456 或 aid=123456 或 /video/av123456 */
    private val AV_REGEX = Regex("""(?:av|aid=|/av)(\d+)""", RegexOption.IGNORE_CASE)

    /** 空间 mid：space.bilibili.com/123456 */
    private val MID_REGEX = Regex("""space\.bilibili\.com/(\d+)""")

    /** b23.tv 短链 */
    private val SHORT_LINK_REGEX = Regex("""https?://b23\.tv/[0-9A-Za-z]+""")

    /** 纯数字（当作 mid 或 aid 判断） */
    private val PURE_NUMBER_REGEX = Regex("""^\d{1,15}$""")

    fun parseBvid(text: String): String? = BV_REGEX.find(text)?.value

    fun parseAid(text: String): Long? =
        AV_REGEX.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun parseMid(text: String): Long? =
        MID_REGEX.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: text.trim().takeIf { PURE_NUMBER_REGEX.matches(it) }?.toLongOrNull()

    fun findShortLink(text: String): String? = SHORT_LINK_REGEX.find(text)?.value

    /** 是不是一个可识别的视频标识（BV / av / 短链） */
    fun looksLikeVideo(text: String): Boolean =
        parseBvid(text) != null || parseAid(text) != null || findShortLink(text) != null

    /**
     * 从跳转后的最终 URL 里再解析一次 BV / av。
     * b23.tv 短链 302 之后的地址形如 https://www.bilibili.com/video/BV1xx411c7mD?...
     */
    fun parseFromResolvedUrl(url: String): String? = parseBvid(url) ?: parseAid(url)?.let { "av$it" }

    /** "mm:ss" / "h:mm:ss" → 毫秒；解析失败返回 0 */
    fun parseDurationText(text: String): Long {
        val parts = text.trim().split(':')
        if (parts.isEmpty()) return 0L
        val numbers = parts.map { it.trim().toLongOrNull() ?: return 0L }
        val seconds = when (numbers.size) {
            1 -> numbers[0]
            2 -> numbers[0] * 60 + numbers[1]
            3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
            else -> return 0L
        }
        return seconds * 1000L
    }
}
