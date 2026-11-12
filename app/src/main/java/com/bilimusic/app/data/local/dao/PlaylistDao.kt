package com.bilimusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bilimusic.app.data.local.entity.PlaylistEntity
import com.bilimusic.app.domain.model.PlaylistSourceType
import kotlinx.coroutines.flow.Flow

/** 歌单 + 统计（曲目数、总时长），列表页用 */
data class PlaylistWithStats(
    @Embedded val playlist: PlaylistEntity,
    val songCount: Int,
    val totalDurationMs: Long,
)

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.*,
               COUNT(s.id) AS songCount,
               COALESCE(SUM(s.durationMs), 0) AS totalDurationMs
        FROM playlist p
        LEFT JOIN song s ON s.playlistId = p.id AND s.isInvalid = 0
        GROUP BY p.id
        ORDER BY p.sortOrder ASC, p.createdAt DESC
        """,
    )
    fun observeAllWithStats(): Flow<List<PlaylistWithStats>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): PlaylistEntity?

    /** 按来源查找歌单：同一个收藏夹/合集重复导入时复用同一个歌单，实现增量去重 */
    @Query("SELECT * FROM playlist WHERE sourceType = :type AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(type: PlaylistSourceType, sourceId: String): PlaylistEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist")
    suspend fun maxSortOrder(): Int

    /** 返回新插入的行 id */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlist SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    /** 拖动排序：直接改 sortOrder */
    @Query("UPDATE playlist SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE playlist SET coverUrl = :coverUrl WHERE id = :id")
    suspend fun updateCover(id: Long, coverUrl: String?)

    @Query("UPDATE playlist SET lastImportAt = :at WHERE id = :id")
    suspend fun updateLastImportAt(id: Long, at: Long)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM playlist")
    suspend fun count(): Int
}
