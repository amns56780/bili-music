package com.bilimusic.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 收藏夹列表（/x/v3/fav/folder/created/list-all） */
@Serializable
data class FavFolderListData(
    val count: Int = 0,
    val list: List<FavFolderDto>? = null,
)

@Serializable
data class FavFolderDto(
    val id: Long = 0L,
    val fid: Long = 0L,
    val mid: Long = 0L,
    /** attr == 0 公开；其它值为私密 */
    val attr: Int = 0,
    val title: String = "",
    @SerialName("media_count")
    val mediaCount: Int = 0,
)

/** 收藏夹内容（/x/v3/fav/resource/list） */
@Serializable
data class FavResourceListData(
    val info: FavInfoDto? = null,
    val medias: List<FavMediaDto>? = null,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

@Serializable
data class FavInfoDto(
    val id: Long = 0L,
    val title: String = "",
    @SerialName("media_count")
    val mediaCount: Int = 0,
)

@Serializable
data class FavMediaDto(
    /** aid */
    val id: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    /** 秒 */
    val duration: Int = 0,
    /** attr != 0 表示已失效 */
    val attr: Int = 0,
    val upper: UpperDto? = null,
)

@Serializable
data class UpperDto(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
)

/** 稍后再看（/x/v2/history/toview） */
@Serializable
data class ToViewData(
    val count: Int = 0,
    val list: List<ToViewItemDto>? = null,
)

@Serializable
data class ToViewItemDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val title: String = "",
    val pic: String = "",
    /** 秒 */
    val duration: Int = 0,
    val owner: UpperDto? = null,
)

/** UP 主投稿（/x/space/wbi/arc/search） */
@Serializable
data class SpaceArcSearchData(
    val list: SpaceArcListDto? = null,
    val page: SpacePageDto? = null,
)

@Serializable
data class SpaceArcListDto(
    val vlist: List<SpaceArcDto>? = null,
)

@Serializable
data class SpacePageDto(
    val pn: Int = 1,
    val ps: Int = 30,
    val count: Int = 0,
)

@Serializable
data class SpaceArcDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** "mm:ss" 或 "h:mm:ss" 形式的字符串 */
    val length: String = "",
    val author: String = "",
)
