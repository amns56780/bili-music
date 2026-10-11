package com.bilimusic.app.domain.model

/** FR-2 的导入来源 */
enum class ImportSourceType(val displayName: String) {
    FAV("收藏夹"),
    TOVIEW("稍后再看"),
    UPLOAD("UP主投稿"),
    SEASON("合集"),
}

/** 收藏夹（用于导入页勾选） */
data class FavFolder(
    val id: Long,
    val title: String,
    val mediaCount: Int,
    val isPrivate: Boolean,
)

/** 导入进度（UI 显示「第 n / 共 m 条」） */
sealed interface ImportProgress {
    /** 准备阶段（拉列表 / 建歌单） */
    data class Preparing(val message: String) : ImportProgress

    /** 逐条处理 */
    data class Working(
        val current: Int,
        val total: Int,
        val message: String,
    ) : ImportProgress

    data class Finished(val report: ImportReport) : ImportProgress

    /** 致命错误（未登录 / 风控 / 网络全挂），导入中断 */
    data class Failed(
        val message: String,
        /** true 表示是 B 站风控（-352 / -412），UI 要把该来源置灰 */
        val riskControlled: Boolean = false,
    ) : ImportProgress
}

/** 导入结束报告：任务书要求给出「成功 N 条，跳过失效 M 条」 */
data class ImportReport(
    val playlistId: Long,
    val playlistTitle: String,
    val success: Int,
    val skippedInvalid: Int,
    val skippedDuplicate: Int,
    val failed: Int,
) {
    val summary: String
        get() = buildString {
            append("成功 $success 条")
            if (skippedInvalid > 0) append("，跳过失效 $skippedInvalid 条")
            if (skippedDuplicate > 0) append("，重复跳过 $skippedDuplicate 条")
            if (failed > 0) append("，失败 $failed 条")
        }
}

/** 单曲加入歌单的结果 */
data class AddSongResult(
    val added: Boolean,
    val message: String,
    val songTitle: String? = null,
)
