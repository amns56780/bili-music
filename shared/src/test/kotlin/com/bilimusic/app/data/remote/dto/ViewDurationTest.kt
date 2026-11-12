package com.bilimusic.app.data.remote.dto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单曲条目时长：多P视频的 view.duration 是所有分P之和，不能直接拿来当单曲时长。
 * 回归用例来自用户反馈的「星露谷 OST 全集」（BV1n1421t7XY，147 个分P）。
 */
class ViewDurationTest {

    @Test
    fun 多P视频优先用匹配分P的时长() {
        val view = ViewData(
            cid = 111L,
            duration = 10213, // 接口给的「整段视频」总时长
            pages = listOf(
                ViewPageDto(cid = 111L, page = 1, part = "P1", duration = 147),
                ViewPageDto(cid = 222L, page = 2, part = "P2", duration = 91),
            ),
        )
        assertEquals(147, view.primarySongDurationSeconds())
    }

    @Test
    fun 没有分P时回退到普通时长() {
        val view = ViewData(cid = 1L, duration = 60)
        assertEquals(60, view.primarySongDurationSeconds())
    }

    @Test
    fun cid对不上时回退到第一个分P() {
        val view = ViewData(
            cid = 999L,
            duration = 500,
            pages = listOf(ViewPageDto(cid = 1L, page = 1, duration = 40)),
        )
        assertEquals(40, view.primarySongDurationSeconds())
    }
}
