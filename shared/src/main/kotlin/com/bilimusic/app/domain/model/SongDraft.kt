package com.bilimusic.app.domain.model

/**
 * 写入歌单时的曲目入参（Repository 层入参，UI 不直接构造 Room 实体）。
 */
data class SongDraft(
    val bvid: String,
    val cid: Long,
    val title: String,
    val upperName: String,
    val coverUrl: String?,
    val durationMs: Long,
    val audioQualityId: Int? = null,
    val isInvalid: Boolean = false,
    /** 所属合集/分P组 key；单曲为 null */
    val collectionKey: String? = null,
    /** 所属合集/分P的总集数 */
    val episodeCount: Int = 0,
    val pageIndex: Int = 1,
    /** 本地歌曲的文件 URI；B 站曲目为 null */
    val localUri: String? = null,
)
