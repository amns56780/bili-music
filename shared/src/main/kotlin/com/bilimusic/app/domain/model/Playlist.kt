package com.bilimusic.app.domain.model

/**
 * 歌单领域模型（UI 层只认这个，不直接碰 Room 实体）。
 */
data class Playlist(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val sourceType: PlaylistSourceType,
    val sourceId: String?,
    val createdAt: Long,
    val sortOrder: Int,
    val songCount: Int = 0,
    val totalDurationMs: Long = 0L,
) {
    val totalDurationText: String
        get() = formatDuration(totalDurationMs)
}

/**
 * 曲目领域模型。
 *
 * @param collectionKey 该曲所属合集/分P组的 key（seasonId 或 bvid 或 "bvid#pages"），
 *                      用于 FR-3 选集页的勾选记忆；单曲为 null。
 * @param episodeCount 该曲所属合集/分P组的集数，> 1 时歌单详情页展示「选集」入口。
 */
data class Song(
    val id: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val upperName: String,
    val coverUrl: String?,
    val durationMs: Long,
    val playlistId: Long,
    val audioQualityId: Int?,
    val isInvalid: Boolean,
    val addedAt: Long,
    val sortOrder: Int,
    val collectionKey: String? = null,
    val episodeCount: Int = 0,
    val pageIndex: Int = 1,
    /** 本地歌曲的文件 URI；B 站曲目为 null */
    val localUri: String? = null,
) {
    val durationText: String
        get() = formatDuration(durationMs)

    /** 是不是本地文件（本地歌不取流、不缓存） */
    val isLocal: Boolean
        get() = !localUri.isNullOrBlank()

    /** 播放用唯一 id：bvid + cid，MediaItem.mediaId 也用它 */
    val mediaKey: String
        get() = "$bvid-$cid"

    /** 转成写库入参（歌单间复制/移动、本地导入都用它） */
    fun toDraft(): SongDraft = SongDraft(
        bvid = bvid,
        cid = cid,
        title = title,
        upperName = upperName,
        coverUrl = coverUrl,
        durationMs = durationMs,
        audioQualityId = audioQualityId,
        isInvalid = isInvalid,
        collectionKey = collectionKey,
        episodeCount = episodeCount,
        pageIndex = pageIndex,
        localUri = localUri,
    )
}

/** 时长格式化：mm:ss，超过 1 小时用 h:mm:ss */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
