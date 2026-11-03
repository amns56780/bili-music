package com.bilimusic.app.ui.playlist

import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.PlaylistNameConflict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 歌单重名对话框「挂起等待用户选择」的共用实现。
 *
 * 新建 / 重命名 / 导入三条链路都会碰到重名，交互完全一致：
 * ViewModel 把 [pendingConflict] 暴露给 Screen 弹同一个对话框，
 * 协程用 [awaitChoice] 挂起，UI 点击后调用 [resolve] 唤醒。
 */
class DuplicateNameResolver {

    private val _pendingConflict = MutableStateFlow<PlaylistNameConflict?>(null)

    /** 当前等待用户选择的冲突；null 表示没有弹窗 */
    val pendingConflict: StateFlow<PlaylistNameConflict?> = _pendingConflict.asStateFlow()

    private var pendingChoice: CompletableDeferred<DuplicateNameChoice>? = null

    /**
     * 挂起等待用户选择。
     *
     * 若上一次询问还没有被回答（正常流程不会发生，防御性处理），按「取消」收尾，
     * 避免旧协程永远挂着。
     */
    suspend fun awaitChoice(conflict: PlaylistNameConflict): DuplicateNameChoice {
        val previous = pendingChoice
        pendingChoice = null
        _pendingConflict.value = null
        previous?.complete(DuplicateNameChoice.CANCEL)

        val next = CompletableDeferred<DuplicateNameChoice>()
        pendingChoice = next
        _pendingConflict.value = conflict
        return try {
            next.await()
        } finally {
            if (pendingChoice === next) {
                pendingChoice = null
                _pendingConflict.value = null
            }
        }
    }

    /** UI 在对话框里点了某个选项 */
    fun resolve(choice: DuplicateNameChoice) {
        val pending = pendingChoice
        pendingChoice = null
        _pendingConflict.value = null
        pending?.complete(choice)
    }

    /** 对话框被返回键 / 点击外部关掉：等同于取消 */
    fun dismissAsCancelled() = resolve(DuplicateNameChoice.CANCEL)

    /**
     * 新建 / 重命名共用的完整流程：查重 →（必要时弹窗等待）→ 决定最终标题。
     *
     * @param desiredTitle 已规范化过的目标标题
     * @param findConflict 查重（重命名时要排除当前歌单自己）
     * @param overwrite 用户选「覆盖」时删除同名旧歌单
     * @return 最终标题；null 表示用户取消，调用方不得写任何数据
     */
    suspend fun resolveTitleForWrite(
        desiredTitle: String,
        findConflict: suspend (String) -> PlaylistNameConflict?,
        overwrite: suspend (PlaylistNameConflict) -> Unit,
    ): ResolvedPlaylistName? {
        val conflict = findConflict(desiredTitle) ?: return ResolvedPlaylistName(desiredTitle)
        return when (awaitChoice(conflict)) {
            DuplicateNameChoice.OVERWRITE -> {
                overwrite(conflict)
                ResolvedPlaylistName(conflict.desiredTitle, overwritten = true)
            }

            DuplicateNameChoice.AUTO_RENAME -> ResolvedPlaylistName(conflict.suggestedTitle)
            DuplicateNameChoice.CANCEL -> null
        }
    }
}

/** 新建 / 重命名最终使用的标题（[overwritten] = 覆盖并删除了同名旧歌单） */
data class ResolvedPlaylistName(
    val title: String,
    val overwritten: Boolean = false,
)
