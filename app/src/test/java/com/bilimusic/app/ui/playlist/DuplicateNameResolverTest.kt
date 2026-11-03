package com.bilimusic.app.ui.playlist

import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.PlaylistNameConflict
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重名 resolver：新建 / 重命名 / 导入共用的「弹窗 → 挂起等用户选 → 决定最终标题」逻辑。
 *
 * 覆盖 / 自动重命名 / 取消 三种选择的落库语义在这里锁定，
 * 规则本身（normalize / uniqueTitle / findConflict）由 shared 的 PlaylistNamingTest 覆盖。
 */
class DuplicateNameResolverTest {

    private val conflict = PlaylistNameConflict(
        desiredTitle = "收藏夹",
        existingTitle = "收藏夹",
        existingSongCount = 3,
        suggestedTitle = "收藏夹 (2)",
    )

    private data class AskResult(val name: ResolvedPlaylistName?, val overwriteCalled: Boolean)

    /** 启动一次「查重 → 等用户选择」，返回结果与覆盖回调是否被调用 */
    private fun ask(
        conflict: PlaylistNameConflict?,
        choice: DuplicateNameChoice?,
    ): AskResult = runBlocking {
        withTimeout(5_000) {
            val resolver = DuplicateNameResolver()
            var overwriteCalled = false
            val deferred = async {
                resolver.resolveTitleForWrite(
                    desiredTitle = conflict?.desiredTitle ?: "新歌单",
                    findConflict = { conflict },
                    overwrite = { overwriteCalled = true },
                )
            }
            if (choice != null) {
                while (resolver.pendingConflict.value == null) yield()
                resolver.resolve(choice)
            }
            AskResult(deferred.await(), overwriteCalled)
        }
    }

    @Test
    fun 没有重名时不弹窗_直接用原名字() {
        val result = ask(conflict = null, choice = null)
        assertEquals("新歌单", result.name?.title)
        assertFalse(result.name!!.overwritten)
        assertFalse(result.overwriteCalled)
    }

    @Test
    fun 自动重命名用建议名_不动旧歌单() {
        val result = ask(conflict = conflict, choice = DuplicateNameChoice.AUTO_RENAME)
        assertEquals("收藏夹 (2)", result.name?.title)
        assertFalse(result.name!!.overwritten)
        assertFalse(result.overwriteCalled)
    }

    @Test
    fun 覆盖沿用原名_并调用删除旧歌单() {
        val result = ask(conflict = conflict, choice = DuplicateNameChoice.OVERWRITE)
        assertEquals("收藏夹", result.name?.title)
        assertTrue(result.name!!.overwritten)
        assertTrue(result.overwriteCalled)
    }

    @Test
    fun 取消返回空_不写任何数据() {
        val result = ask(conflict = conflict, choice = DuplicateNameChoice.CANCEL)
        assertNull(result.name)
        assertFalse(result.overwriteCalled)
    }

    @Test
    fun 对话框被返回键关掉等同取消() = runBlocking {
        withTimeout(5_000) {
            val resolver = DuplicateNameResolver()
            val deferred = async {
                resolver.resolveTitleForWrite(
                    desiredTitle = "收藏夹",
                    findConflict = { conflict },
                    overwrite = {},
                )
            }
            while (resolver.pendingConflict.value == null) yield()
            assertEquals(conflict, resolver.pendingConflict.value)
            resolver.dismissAsCancelled()
            assertNull(deferred.await())
            assertNull(resolver.pendingConflict.value)
        }
    }
}
