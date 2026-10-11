package com.bilimusic.app.domain.model

/**
 * FR-3：合集 / 分P 的选集记录（勾选记忆）。
 *
 * @param collectionKey seasonId 或 bvid，用于区分不同合集
 * @param epCid 该集的 cid
 * @param selected 是否勾选（默认全选）
 */
data class Episode(
    val id: Long,
    val playlistId: Long,
    val collectionKey: String,
    val epCid: Long,
    val epBvid: String,
    val epTitle: String,
    val durationMs: Long,
    val selected: Boolean,
    val pageIndex: Int,
)

/**
 * 一个合集/分P组。选集页展示用。
 */
data class EpisodeGroup(
    val collectionKey: String,
    val title: String,
    val episodes: List<Episode>,
    /** 组内统一展示用的 UP 主名与封面（取自歌单里那条代表曲目） */
    val upperName: String = "",
    val coverUrl: String? = null,
) {
    val selectedCount: Int
        get() = episodes.count { it.selected }

    val totalCount: Int
        get() = episodes.size
}
