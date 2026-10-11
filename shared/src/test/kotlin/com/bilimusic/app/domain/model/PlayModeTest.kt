package com.bilimusic.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * FR-4 播放模式的默认值与持久化解析。
 *
 * 背景：用户反馈「随机播放完歌单所有歌就停了」，因为默认模式是「顺序播放」。
 * 现在默认改成**列表循环**，并且解析脏数据也回退到列表循环。
 */
class PlayModeTest {

    @Test
    fun 默认模式是列表循环() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.DEFAULT)
    }

    @Test
    fun 没存过偏好时回退到列表循环() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.fromNameOrRepeatAll(null))
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.fromNameOrRepeatAll(""))
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.fromNameOrRepeatAll("不认识的值"))
    }

    @Test
    fun 能解析出持久化的四种模式() {
        PlayMode.entries.forEach { mode ->
            assertEquals(mode, PlayMode.fromNameOrRepeatAll(mode.name))
        }
    }

    @Test
    fun 四种模式的显示名互不相同() {
        val names = PlayMode.entries.map { it.displayName }
        assertEquals("显示名不能重复，否则用户分不清模式", 4, names.toSet().size)
        assertEquals("顺序播放", PlayMode.SEQUENTIAL.displayName)
        assertEquals("列表循环", PlayMode.REPEAT_ALL.displayName)
        assertEquals("单曲循环", PlayMode.REPEAT_ONE.displayName)
        assertEquals("随机播放", PlayMode.SHUFFLE.displayName)
    }

    @Test
    fun 顺序播放与列表循环是两个不同模式() {
        // 用户反馈：这两个模式的图标原来一模一样，分不清
        assertNotEquals(PlayMode.SEQUENTIAL, PlayMode.REPEAT_ALL)
        assertNotEquals(PlayMode.SEQUENTIAL.displayName, PlayMode.REPEAT_ALL.displayName)
    }
}
