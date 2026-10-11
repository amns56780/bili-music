package com.bilimusic.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BV / AV / 链接解析的单测（纯逻辑，不需要设备）。
 */
class BiliLinkParserTest {

    @Test
    fun `解析标准 BV 号`() {
        assertEquals("BV1xx411c7mD", BiliLinkParser.parseBvid("BV1xx411c7mD"))
        assertEquals("BV1xx411c7mD", BiliLinkParser.parseBvid("https://www.bilibili.com/video/BV1xx411c7mD?p=1"))
        assertEquals("BV1xx411c7mD", BiliLinkParser.parseBvid("【标题】 https://b23.tv/abc 看看这个 BV1xx411c7mD 分享"))
    }

    @Test
    fun `解析 av 号`() {
        assertEquals(123456L, BiliLinkParser.parseAid("av123456"))
        assertEquals(123456L, BiliLinkParser.parseAid("https://www.bilibili.com/video/av123456"))
        assertEquals(987654L, BiliLinkParser.parseAid("https://www.bilibili.com/video/aid=987654"))
        assertNull(BiliLinkParser.parseAid("BV1xx411c7mD"))
    }

    @Test
    fun `解析 UP 主 mid`() {
        assertEquals(12345678L, BiliLinkParser.parseMid("12345678"))
        assertEquals(12345L, BiliLinkParser.parseMid("https://space.bilibili.com/12345/video"))
        assertNull(BiliLinkParser.parseMid("abc"))
    }

    @Test
    fun `识别 b23 tv 短链`() {
        assertEquals("https://b23.tv/aBc123", BiliLinkParser.findShortLink("看这个 https://b23.tv/aBc123 好玩"))
        assertNull(BiliLinkParser.findShortLink("https://www.bilibili.com/video/BV1xx411c7mD"))
    }

    @Test
    fun `从短链跳转后的地址里解析 BV`() {
        val resolved = "https://www.bilibili.com/video/BV1xx411c7mD?share_source=copy_web"
        assertEquals("BV1xx411c7mD", BiliLinkParser.parseFromResolvedUrl(resolved))
    }

    @Test
    fun `解析时长文本`() {
        assertEquals(0L, BiliLinkParser.parseDurationText(""))
        assertEquals(45_000L, BiliLinkParser.parseDurationText("0:45"))
        assertEquals(3_725_000L, BiliLinkParser.parseDurationText("1:02:05"))
        assertEquals(0L, BiliLinkParser.parseDurationText("abc"))
    }

    @Test
    fun `looksLikeVideo 判定`() {
        assertEquals(true, BiliLinkParser.looksLikeVideo("BV1xx411c7mD"))
        assertEquals(true, BiliLinkParser.looksLikeVideo("av123"))
        assertEquals(true, BiliLinkParser.looksLikeVideo("https://b23.tv/xyz"))
        assertEquals(false, BiliLinkParser.looksLikeVideo("今天天气不错"))
    }
}
