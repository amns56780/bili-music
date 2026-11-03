package com.bilimusic.desktop.data

import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.PlaylistNaming
import com.bilimusic.app.domain.model.PlaylistSourceType

/**
 * 导入歌单时「目标歌单」的解析结果。
 *
 * 分两阶段：先 [resolveImportPlaylist] 只做判断 / 弹窗，**不写库**；
 * 等数据真的拉取成功了再 [commit] 落库。这样用户点「取消」或者拉到一半失败，
 * 都不会在库里留下半成品歌单（覆盖场景也不会先把旧歌单删掉）。
 */
internal sealed interface PlaylistTarget {
    /** 最终使用的歌单标题（自动重命名后可能和来源标题不同） */
    val title: String

    /** 已有歌单，直接复用（同一来源重复导入，不弹窗） */
    data class Existing(val id: Long, override val title: String) : PlaylistTarget

    /**
     * 需要新建的歌单。
     * @param overwriteTitle 非空表示「覆盖」：先删掉这个同名旧歌单（连同它的曲目）
     * @param sourceTitle 导入来源的原始标题（自动改名后，下次导入仍然认得出同一来源）
     */
    data class Create(
        override val title: String,
        val overwriteTitle: String? = null,
        val sourceTitle: String? = null,
    ) : PlaylistTarget
}

/**
 * 找到或决定导入目标歌单（ImportService 的四个来源 + 本地音乐导入共用）。
 *
 * - 同一来源已经导入过（[PlaylistStore.findBySource] 命中）：直接复用，不弹窗；
 * - 没有重名：直接新建；
 * - 重名：通过 [conflicts] 挂起询问用户，按选择得到覆盖 / 自动重命名 / 取消。
 *
 * @return null 表示用户取消，调用方应中止本次导入（此时还不写任何数据）
 */
internal suspend fun resolveImportPlaylist(
    store: PlaylistStore,
    conflicts: PlaylistConflictResolver,
    title: String,
    sourceType: PlaylistSourceType,
    /** 分享链接这类「每次都要新建」的场景传 false，避免误复用同名歌单 */
    reuseBySource: Boolean = true,
): PlaylistTarget? {
    val desired = PlaylistNaming.normalize(title) ?: PlaylistNaming.FALLBACK_TITLE
    if (reuseBySource) {
        store.findBySource(sourceType, desired)?.let { return PlaylistTarget.Existing(it.id, it.title) }
    }
    val conflict = store.findConflict(desired)
        ?: return PlaylistTarget.Create(desired, sourceTitle = desired)
    val choice = conflicts.resolve(conflict)
    val finalTitle = PlaylistNaming.resolveConflict(conflict, choice) ?: return null
    return PlaylistTarget.Create(
        title = finalTitle,
        overwriteTitle = if (choice == DuplicateNameChoice.OVERWRITE) conflict.existingTitle else null,
        sourceTitle = desired,
    )
}

/** 真正落库：复用已有歌单，或（覆盖时先删旧同名歌单再）新建 */
internal fun PlaylistTarget.commit(store: PlaylistStore, sourceType: PlaylistSourceType): Long = when (this) {
    is PlaylistTarget.Existing -> id
    is PlaylistTarget.Create -> {
        overwriteTitle?.let { store.deletePlaylistByTitle(it) }
        store.createPlaylist(title, sourceType, sourceTitle = sourceTitle)
    }
}
