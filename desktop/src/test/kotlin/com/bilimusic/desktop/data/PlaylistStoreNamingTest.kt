package com.bilimusic.desktop.data

import com.bilimusic.app.domain.model.PlaylistSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 桌面端歌单重名 / 覆盖 / 启动迁移的单元测试（只测 [PlaylistStore]，不依赖 Compose）。
 *
 * UI 对话框的选择语义由 shared 的 PlaylistNamingTest 覆盖，这里验证存储层有没有正确落实：
 * 规范化、判重上下文、覆盖删除（连同曲目）、历史重名迁移只跑一次。
 */
class PlaylistStoreNamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun freshStore(name: String = "playlists.json") = PlaylistStore(File(tmp.root, name))

    private fun song(bvid: String, cid: Long, title: String) = NewSong(bvid = bvid, cid = cid, title = title)

    // ---------------- 启动迁移 ----------------

    @Test
    fun 启动时把历史重名歌单自动改成命名后缀() {
        val file = File(tmp.root, "playlists.json")
        file.writeText(
            """
            {
              "playlists": [
                {"id": 1, "title": "收藏夹", "sourceType": "MANUAL", "createdAt": 1},
                {"id": 2, "title": "收藏夹", "sourceType": "FAV", "createdAt": 2},
                {"id": 3, "title": "收藏夹（2）", "sourceType": "MANUAL", "createdAt": 3}
              ],
              "songs": [
                {"id": 1, "playlistId": 2, "bvid": "BV1", "cid": 1, "title": "歌", "sortOrder": 0}
              ],
              "nextPlaylistId": 4,
              "nextSongId": 2
            }
            """.trimIndent(),
        )

        val store = PlaylistStore(file)
        assertEquals(
            listOf("收藏夹", "收藏夹 (2)", "收藏夹 (3)"),
            store.playlists.value.map { it.title },
        )
        // 迁移只改标题，曲目还挂在原来的歌单上
        assertEquals(1, store.songsOf(2L).size)
        // 迁移标记要落盘，避免每次启动重跑
        assertTrue(file.readText().contains("titleMigrationVersion"))

        // 二次启动：标题保持不变
        val reopened = PlaylistStore(file)
        assertEquals(
            listOf("收藏夹", "收藏夹 (2)", "收藏夹 (3)"),
            reopened.playlists.value.map { it.title },
        )
    }

    // ---------------- 规范化 / 判重 ----------------

    @Test
    fun 新建歌单统一规范化标题() {
        val store = freshStore()
        store.createPlaylist("  收藏夹（2）  ")
        assertEquals("收藏夹 (2)", store.playlists.value.single().title)
    }

    @Test
    fun 重名检查返回曲目数与建议名且能排除自己() {
        val store = freshStore()
        val first = store.createPlaylist("收藏夹")
        store.addSongs(first, listOf(song("BV1", 1, "一"), song("BV2", 2, "二"), song("BV3", 3, "三")))
        store.createPlaylist("收藏夹 (2)")

        val conflict = store.findConflict("  收藏夹 ")
        assertNotNull(conflict)
        assertEquals(3, conflict!!.existingSongCount)
        assertEquals("收藏夹 (3)", conflict.suggestedTitle)

        // 重命名时排除自己：改成跟原文一样的名字不算冲突
        assertNull(store.findConflict("收藏夹", exceptId = first))
        // 全角括号和半角括号归一后再判重
        assertTrue(store.titleExists("收藏夹（2）"))
    }

    // ---------------- 覆盖 ----------------

    @Test
    fun 覆盖新建会删掉同名旧歌单和它的曲目() {
        val store = freshStore()
        val old = store.createPlaylist("收藏夹", PlaylistSourceType.FAV)
        store.addSongs(old, listOf(song("BV1", 1, "一"), song("BV2", 2, "二")))

        val newId = store.createPlaylistOverwriting("收藏夹", PlaylistSourceType.MANUAL)

        assertEquals(1, store.playlists.value.size)
        assertEquals(newId, store.playlists.value.single().id)
        assertEquals("收藏夹", store.playlists.value.single().title)
        // 旧歌单的曲目一起删掉，不留孤儿记录
        assertTrue(store.songs.value.isEmpty())
    }

    @Test
    fun 覆盖重命名会删掉另一个同名歌单但保留自己的曲目() {
        val store = freshStore()
        val keep = store.createPlaylist("新歌单")
        val overwritten = store.createPlaylist("收藏夹")
        store.addSongs(keep, listOf(song("BV1", 1, "自己的")))
        store.addSongs(overwritten, listOf(song("BV2", 2, "被覆盖的")))

        store.renamePlaylistOverwriting(keep, "收藏夹")

        assertEquals(1, store.playlists.value.size)
        assertEquals("收藏夹", store.playlists.value.single().title)
        assertEquals(listOf("自己的"), store.songsOf(keep).map { it.title })
    }

    // ---------------- 同一来源复用 ----------------

    @Test
    fun 自动改名后的导入歌单仍能按来源复用() {
        val store = freshStore()
        // 手动歌单占了「收藏夹」这个名字
        store.createPlaylist("收藏夹", PlaylistSourceType.MANUAL)
        // 导入「收藏夹」时用户选了自动重命名：标题变成「收藏夹 (2)」，但记住来源标题
        val imported = store.createPlaylist("收藏夹 (2)", PlaylistSourceType.FAV, sourceTitle = "收藏夹")

        assertEquals(imported, store.findBySource(PlaylistSourceType.FAV, "收藏夹")?.id)
        assertNull(store.findBySource(PlaylistSourceType.TOVIEW, "收藏夹"))
    }
}
