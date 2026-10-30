package com.bilimusic.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * 播放时阻止电脑自动熄屏 / 睡眠。
 *
 * 走 Win32 的 SetThreadExecutionState：
 *   ES_CONTINUOUS      —— 状态一直保持到下次调用
 *   ES_SYSTEM_REQUIRED —— 阻止系统睡眠
 *   ES_DISPLAY_REQUIRED—— 阻止显示器关闭（熄屏）
 *
 * 注意这是**线程级** API：本封装固定用 synchronized 在同一线程语义下启用/恢复，
 * 且进程退出后状态自动清除，不会留下副作用。
 */
object KeepAwake {

    private const val ES_CONTINUOUS: Long = 0x80000000L
    private const val ES_SYSTEM_REQUIRED: Long = 0x00000001L
    private const val ES_DISPLAY_REQUIRED: Long = 0x00000002L

    private interface Kernel32 : Library {
        fun SetThreadExecutionState(flags: Long): Long
    }

    private val kernel32: Kernel32? = runCatching {
        Native.load("kernel32", Kernel32::class.java)
    }.getOrNull()

    @Volatile
    private var active = false

    /** 播放开始：阻止熄屏 + 睡眠 */
    @Synchronized
    fun enable() {
        if (active) return
        active = true
        val result = runCatching {
            kernel32?.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
        }.getOrNull()
        com.bilimusic.app.util.AppLog.i("KeepAwake", "已阻止自动熄屏/睡眠 (result=" + result + ")")
    }

    /** 暂停/停止：恢复系统的自动熄屏 */
    @Synchronized
    fun disable() {
        if (!active) return
        active = false
        runCatching { kernel32?.SetThreadExecutionState(ES_CONTINUOUS) }
        com.bilimusic.app.util.AppLog.i("KeepAwake", "已恢复系统自动熄屏")
    }
}
