package com.bilimusic.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌单重名规则（双端共用）。
 *
 * 用户需求：歌单不能重名；碰到重名选择「覆盖」或「像 Windows 一样加 (n)」。
 */
class PlaylistNamingTest {

    // ---------------- normalize ----------------

    @Test
    fun 空白名字被拒绝() {
        assertNull(PlaylistNaming.normalize("   "))
        assertNull(PlaylistNaming.normalize(""))
        assertEquals("歌单", PlaylistNaming.normalize("  歌单  "))
    }

    // ---------------- uniqueTitle ----------------

    @Test
    fun 不重名时保持原样() {
        assertEquals("收藏夹", PlaylistNaming.uniqueTitle("收藏夹", listOf("歌单", "丁曲")))
    }

    @Test
    fun 第一个重名加2() {
        assertEquals("收藏夹 (2)", PlaylistNaming.uniqueTitle("收藏夹", listOf("收藏夹")))
    }

    @Test
    fun 连续重名递增序号() {
        assertEquals(
            "收藏夹 (4)",
            PlaylistNaming.uniqueTitle("收藏夹", listOf("收藏夹", "收藏夹 (2)", "收藏夹 (3)")),
        )
    }

    @Test
    fun 中间缺号时补缺号() {
        assertEquals(
            "收藏夹 (2)",
            PlaylistNaming.uniqueTitle("收藏夹", listOf("收藏夹", "收藏夹 (3)")),
        )
    }

    @Test
    fun 已经带后缀时按根名继续编号() {
        assertEquals(
            "收藏夹 (3)",
            PlaylistNaming.uniqueTitle("收藏夹 (2)", listOf("收藏夹", "收藏夹 (2)")),
        )
    }

    @Test
    fun 全角括号后缀也能识别() {
        assertEquals("收藏夹", PlaylistNaming.stripDuplicateSuffix("收藏夹（2）"))
        assertEquals(
            "收藏夹 (3)",
            PlaylistNaming.uniqueTitle("收藏夹（2）", listOf("收藏夹", "收藏夹 (2)")),
        )
    }

    @Test
    fun 空输入有兜底标题() {
        assertEquals(PlaylistNaming.FALLBACK_TITLE, PlaylistNaming.uniqueTitle("   ", emptyList()))
    }

    // ---------------- findConflict / resolveConflict ----------------

    private fun existing(vararg items: Pair<String, Int>) =
        items.map { ExistingPlaylistTitle(it.first, it.second) }

    @Test
    fun 没重名时没有冲突() {
        assertNull(PlaylistNaming.findConflict("新歌单", existing("收藏夹" to 5)))
    }

    @Test
    fun 重名冲突带上曲目数与建议名() {
        val conflict = PlaylistNaming.findConflict(
            "收藏夹",
            existing("收藏夹" to 12, "收藏夹 (2)" to 3),
        )
        requireNotNull(conflict)
        assertEquals("收藏夹", conflict.desiredTitle)
        assertEquals("收藏夹", conflict.existingTitle)
        assertEquals(12, conflict.existingSongCount)
        assertEquals("收藏夹 (3)", conflict.suggestedTitle)
    }

    @Test
    fun 重命名时建议名会避开当前歌单自己的现名() {
        // 现有：X、X (2)（自己是 X (2)），把 X (2) 改名成 X → 建议名不能还是 X (2)
        val conflict = PlaylistNaming.findConflict(
            desired = "X",
            existing = listOf(ExistingPlaylistTitle("X", 3)),
            extraSuggestionTitles = listOf("X (2)"),
        )
        requireNotNull(conflict)
        assertEquals("X (3)", conflict.suggestedTitle)
    }

    @Test
    fun 输入前后空白按重名处理() {
        val conflict = PlaylistNaming.findConflict("  收藏夹 ", existing("收藏夹" to 1))
        requireNotNull(conflict)
        assertEquals("收藏夹", conflict.desiredTitle)
    }

    @Test
    fun 覆盖沿用原名_自动重命名用建议名_取消返回空() {
        val conflict = PlaylistNameConflict("收藏夹", "收藏夹", 7, "收藏夹 (2)")
        assertEquals(
            "收藏夹",
            PlaylistNaming.resolveConflict(conflict, DuplicateNameChoice.OVERWRITE),
        )
        assertEquals(
            "收藏夹 (2)",
            PlaylistNaming.resolveConflict(conflict, DuplicateNameChoice.AUTO_RENAME),
        )
        assertNull(PlaylistNaming.resolveConflict(conflict, DuplicateNameChoice.CANCEL))
    }
}
