package com.bilimusic.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 视频详情（/x/web-interface/view）：cid、分P、合集都在这里 */
@Serializable
data class ViewData(
    val aid: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val title: String = "",
    val pic: String = "",
    /** 秒 */
    val duration: Int = 0,
    val owner: UpperDto? = null,
    /** 分P */
    val pages: List<ViewPageDto>? = null,
    /** 合集 / 系列 */
    @SerialName("ugc_season")
    val ugcSeason: UgcSeasonDto? = null,
)

@Serializable
data class ViewPageDto(
    val cid: Long = 0L,
    val page: Int = 1,
    val part: String = "",
    /** 秒 */
    val duration: Int = 0,
)

@Serializable
data class UgcSeasonDto(
    val id: Long = 0L,
    val title: String = "",
    val sections: List<UgcSectionDto>? = null,
)

@Serializable
data class UgcSectionDto(
    val id: Long = 0L,
    val title: String = "",
    val episodes: List<UgcEpisodeDto>? = null,
)

@Serializable
data class UgcEpisodeDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val title: String = "",
    val pic: String = "",
    /** 秒 */
    val duration: Int = 0,
    /** 剧集对应的稿件信息（标题通常比 episode.title 更有意义） */
    val arc: UgcArcDto? = null,
)

@Serializable
data class UgcArcDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** 秒 */
    val duration: Int = 0,
)

/** 合集内容（/x/polymer/web-space/seasons_archives_list），用于补充 / 兜底 */
@Serializable
data class SeasonArchivesData(
    val archives: List<SeasonArchiveDto>? = null,
    val page: SeasonPageDto? = null,
)

@Serializable
data class SeasonPageDto(
    @SerialName("page_num")
    val pageNum: Int = 1,
    @SerialName("page_size")
    val pageSize: Int = 30,
    val total: Int = 0,
)

@Serializable
data class SeasonArchiveDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val title: String = "",
    val pic: String = "",
    /** 秒 */
    val duration: Int = 0,
)
