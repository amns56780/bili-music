package com.bilimusic.app.domain.model

/**
 * 歌单重名处理规则 —— 双端共用的唯一实现。
 *
 * 之前两端各写一套 trim / 重名判断，桌面端甚至完全不检查重名，导致同名歌单可以随便建；
 * 现在统一收口到这里，UI 只负责把 [PlaylistNameConflict] 渲染成「覆盖 / 自动重命名」对话框。
 *
 * 自动重命名沿用 Windows 资源管理器的习惯：第一个重名是「名字 (2)」，再往后「名字 (3)」…
 * （数字前有一个半角空格，括号用半角；用户自己输入的全角括号也能被识别并去掉后再编号）。
 */
object PlaylistNaming {

    /** 自动重命名的起始序号（Windows 也是从 2 开始） */
    const val FIRST_SUFFIX = 2

    /** 名字没给时兜底用的标题 */
    const val FALLBACK_TITLE = "未命名歌单"

    /** 匹配标题末尾的「(n)」或「（n）」 */
    private val SUFFIX_REGEX = Regex("""^(.+?)\s*[（(](\d+)[）)]$""")

    /**
     * 规范化用户输入：去首尾空白；空白返回 null（调用方提示「歌单名不能为空」）。
     *
     * 末尾的「(n)」统一成半角形式（「收藏夹（2）」→「收藏夹 (2)」），
     * 否则半角/全角两种写法会在列表里看着重名、但判重判不出来。
     */
    fun normalize(rawTitle: String): String? {
        val trimmed = rawTitle.trim().ifEmpty { return null }
        val match = SUFFIX_REGEX.matchEntire(trimmed) ?: return trimmed
        val root = match.groupValues[1].trimEnd().ifEmpty { return trimmed }
        val index = match.groupValues[2]
        return "$root ($index)"
    }

    /**
     * 生成一个不与 [existingTitles] 重名的标题。
     *
     * - "X" 不存在 → 直接返回 "X"
     * - "X" 存在、"X (2)" 不存在 → "X (2)"
     * - "X"、"X (2)" 都存在 → "X (3)"
     * - 想叫 "X (2)" 但已存在 → 按根名 "X" 继续往后找（不会出现 "X (2) (2)"）
     */
    fun uniqueTitle(desired: String, existingTitles: Collection<String>): String {
        val existing = existingTitles.asSequence().mapNotNull { normalize(it) }.toSet()
        val base = normalize(desired) ?: FALLBACK_TITLE
        if (base !in existing) return base

        val root = stripDuplicateSuffix(base) ?: base
        var index = FIRST_SUFFIX
        while ("$root ($index)" in existing) index++
        return "$root ($index)"
    }

    /** 去掉末尾的「(n)」后缀；没有后缀返回 null */
    fun stripDuplicateSuffix(title: String): String? {
        val match = SUFFIX_REGEX.matchEntire(title.trim()) ?: return null
        return match.groupValues[1].trimEnd().ifEmpty { null }
    }

    /**
     * 检查 [desired] 是否与已有歌单重名；重名时返回后续对话框需要的上下文。
     * [existing] 里第一条同名项就是「将要被覆盖」的那个歌单。
     */
    fun findConflict(
        desired: String,
        existing: Collection<ExistingPlaylistTitle>,
        /**
         * 计算建议名时额外算进去的标题。
         *
         * 重命名场景传当前歌单自己的标题：判重时要排除自己（否则改回原名也会弹窗），
         * 但建议名必须把自己的现名也算作「已占用」，不然会出现
         * 「把 X (2) 改成 X → 建议自动重命名为 X (2) → 选了等于没改」这种怪事。
         */
        extraSuggestionTitles: Collection<String> = emptyList(),
    ): PlaylistNameConflict? {
        val title = normalize(desired) ?: return null
        val match = existing.firstOrNull { normalize(it.title) == title } ?: return null
        return PlaylistNameConflict(
            desiredTitle = title,
            existingTitle = normalize(match.title) ?: match.title,
            existingSongCount = match.songCount,
            suggestedTitle = uniqueTitle(title, existing.map { it.title } + extraSuggestionTitles),
        )
    }

    /**
     * 用户做出选择后，本次操作最终使用的标题。
     * 返回 null 表示取消（调用方中止本次创建/导入，不写任何数据）。
     */
    fun resolveConflict(conflict: PlaylistNameConflict, choice: DuplicateNameChoice): String? =
        when (choice) {
            DuplicateNameChoice.OVERWRITE -> conflict.desiredTitle
            DuplicateNameChoice.AUTO_RENAME -> conflict.suggestedTitle
            DuplicateNameChoice.CANCEL -> null
        }
}

/** 重名判断只需要标题和曲目数，不绑定两端各自的歌单模型 */
data class ExistingPlaylistTitle(val title: String, val songCount: Int = 0)

/**
 * 一次重名冲突的完整上下文。
 *
 * @param desiredTitle 用户本来的名字（覆盖时沿用）
 * @param existingTitle 已存在的同名歌单标题（实际同名，仅大小写/空白可能不同）
 * @param existingSongCount 已存在歌单的曲目数，用于对话框提示「覆盖会删除这 N 首」
 * @param suggestedTitle 自动重命名建议值
 */
data class PlaylistNameConflict(
    val desiredTitle: String,
    val existingTitle: String,
    val existingSongCount: Int,
    val suggestedTitle: String,
)

/** 用户在重名对话框里的选择 */
enum class DuplicateNameChoice {
    /** 覆盖：删除已有同名歌单（连同曲目），再用原名字新建/写入 */
    OVERWRITE,

    /** 自动重命名：用 PlaylistNaming.uniqueTitle 生成的新名字 */
    AUTO_RENAME,

    /** 取消：本次操作不写任何数据 */
    CANCEL,
}
