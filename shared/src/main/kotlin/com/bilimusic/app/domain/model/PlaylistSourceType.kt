package com.bilimusic.app.domain.model

/**
 * 歌单来源类型。对应 FR-2 的 4 种导入来源 + App 内手动新建。
 */
enum class PlaylistSourceType {
    /** 我收藏的视频（收藏夹） */
    FAV,

    /** 稍后再看 */
    TOVIEW,

    /** UP 主投稿 */
    UPLOAD,

    /** 合集 / 系列 */
    SEASON,

    /** App 内手动新建 */
    MANUAL,
    ;

    val displayName: String
        get() = when (this) {
            FAV -> "收藏夹"
            TOVIEW -> "稍后再看"
            UPLOAD -> "UP主投稿"
            SEASON -> "合集"
            MANUAL -> "手动创建"
        }
}
