package com.bilimusic.desktop.data

import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.PlaylistNameConflict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 歌单重名时的询问接口。
 *
 * 导入 / 本地导入都可能建歌单，而它们跑在协程里，所以这里用 suspend 形式：
 * 实现方让用户看到「覆盖 / 自动重命名 / 取消」对话框，用户点完按钮才返回，
 * 调用协程就停在这里等，不需要把导入流程拆成状态机。
 */
fun interface PlaylistConflictResolver {
    suspend fun resolve(conflict: PlaylistNameConflict): DuplicateNameChoice
}

/**
 * 桌面 UI 侧的实现（Main.kt 创建，Compose 对话框渲染）：
 *
 * - [pending] 是给 Compose 用的状态：非空时显示重名对话框；
 * - 内部用 [CompletableDeferred] 挂起调用方协程，用户点击后 [answer] 唤醒；
 * - 对话框被关掉（点遮罩 / Esc）由 UI 传 CANCEL，等价于「取消本次操作」。
 *
 * 导入是串行的，同一时刻只会有一个待决询问；多余的 [answer] 不会出问题
 * （Deferred 只认第一次完成）。
 */
class UiPlaylistConflictResolver : PlaylistConflictResolver {

    private val _pending = MutableStateFlow<PlaylistNameConflict?>(null)
    val pending: StateFlow<PlaylistNameConflict?> = _pending.asStateFlow()

    private var answer: CompletableDeferred<DuplicateNameChoice>? = null

    override suspend fun resolve(conflict: PlaylistNameConflict): DuplicateNameChoice {
        // 极端情况下（用户同时点了两个导入）会同时有两次询问：上一次先按取消结束，
        // 免得那个协程永远挂在 await 上；对话框只显示最新一次。
        answer?.complete(DuplicateNameChoice.CANCEL)
        val deferred = CompletableDeferred<DuplicateNameChoice>()
        answer = deferred
        _pending.value = conflict
        return try {
            deferred.await()
        } finally {
            // 只有自己还是当前询问时才清状态，避免把后一次询问的对话框关掉
            if (answer === deferred) {
                _pending.value = null
                answer = null
            }
        }
    }

    /** 用户在对话框里做了选择（或直接关掉对话框 = 取消） */
    fun answer(choice: DuplicateNameChoice) {
        answer?.complete(choice)
    }

    /** 应用退出等场景的兜底：按「取消」处理，避免导入协程一直挂着 */
    fun cancelPending() {
        answer?.complete(DuplicateNameChoice.CANCEL)
    }
}
