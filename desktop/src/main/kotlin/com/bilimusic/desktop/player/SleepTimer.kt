package com.bilimusic.desktop.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 定时关闭状态 */
data class SleepTimerUiState(
    val running: Boolean = false,
    val remainingMs: Long = 0L,
    /** 到点时是否「等当前这首播完再停」 */
    val stopAfterCurrent: Boolean = false,
) {
    /** mm:ss */
    val remainingText: String
        get() {
            if (remainingMs <= 0) return "--:--"
            val total = remainingMs / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }
}

/**
 * 定时关闭（FR-6 桌面版）。
 *
 * 与安卓端一致的点：
 * - 到点时**不硬切**：默认「等当前这首播完再停」，避免听到一半被掐断
 * - 用户主动操作（取消/重新开始）都以内存状态为准，不做跨进程恢复
 *   （安卓端踩过「重启后 elapsedRealtime 失效造出幽灵定时」的坑，
 *     桌面端这里更简单：定时只活在本次运行内，不写盘，所以不存在幽灵定时）
 */
class SleepTimer(
    private val scope: CoroutineScope,
    /** 到点回调；参数是「是否等当前曲播完再停」 */
    private val onFire: (stopAfterCurrent: Boolean) -> Unit,
) {

    private val _state = MutableStateFlow(SleepTimerUiState())
    val state: StateFlow<SleepTimerUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var deadlineMs = 0L

    /** 开始倒计时（分钟）。stopAfterCurrent = true 时等当前曲播完再停 */
    fun start(minutes: Int, stopAfterCurrent: Boolean = true) {
        cancel()
        deadlineMs = System.currentTimeMillis() + minutes * 60_000L
        _state.value = SleepTimerUiState(
            running = true,
            remainingMs = deadlineMs - System.currentTimeMillis(),
            stopAfterCurrent = stopAfterCurrent,
        )
        job = scope.launch {
            while (true) {
                val remain = deadlineMs - System.currentTimeMillis()
                if (remain <= 0) break
                _state.value = _state.value.copy(remainingMs = remain)
                delay(500)
            }
            _state.value = _state.value.copy(running = false, remainingMs = 0L)
            onFire(_state.value.stopAfterCurrent)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        deadlineMs = 0L
        _state.value = SleepTimerUiState()
    }

    fun toggle(minutes: Int, stopAfterCurrent: Boolean = true) {
        if (_state.value.running) cancel() else start(minutes, stopAfterCurrent)
    }
}
