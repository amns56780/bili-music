package com.bilimusic.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * 沉浸模式下的鼠标自动隐藏。
 *
 * Compose Desktop 没有「隐藏光标」的 API（PointerIcon 只能换图标），
 * 所以直接调 user32 的 SetCursor(NULL) —— 它只影响**当前**光标形状，
 * 下次系统更新光标（移动/重绘）会自动恢复默认箭头，不会把用户光标弄丢。
 *
 * 为什么不用 ShowCursor(false)：它维护一个全局计数器，进程异常退出时
 * 计数器留在负值，用户的光标就再也出不来了。
 */
object CursorHider {

    private const val IDC_ARROW = 32512L

    /** user32 里 JNA 的 User32 没暴露这两个函数，单独声明最小接口 */
    private interface User32Cursor : Library {
        fun SetCursor(hCursor: Pointer?): Pointer?
        fun LoadCursorW(hInstance: Pointer?, lpCursorName: Pointer?): Pointer?
    }

    private val user32: User32Cursor? = runCatching {
        Native.load("user32", User32Cursor::class.java)
    }.onFailure {
        com.bilimusic.app.util.AppLog.i("CursorHider", "user32 加载失败：" + it.message)
    }.getOrNull()

    @Volatile
    private var hidden = false

    /** 隐藏光标（幂等） */
    @Synchronized
    fun hide() {
        if (hidden) return
        hidden = true
        runCatching { user32?.SetCursor(null) }.onFailure {
            com.bilimusic.app.util.AppLog.i("CursorHider", "隐藏失败：" + it.message)
        }
    }

    /** 恢复标准箭头（幂等） */
    @Synchronized
    fun show() {
        if (!hidden) return
        hidden = false
        runCatching {
            val arrow = user32?.LoadCursorW(null, Pointer(IDC_ARROW))
            if (arrow != null) user32?.SetCursor(arrow)
        }.onFailure {
            com.bilimusic.app.util.AppLog.i("CursorHider", "恢复失败：" + it.message)
        }
    }
}
