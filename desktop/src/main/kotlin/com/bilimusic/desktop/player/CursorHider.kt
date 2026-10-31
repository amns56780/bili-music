package com.bilimusic.desktop.player

/**
 * 沉浸模式下的鼠标自动隐藏。
 *
 * 试过两种"系统级"做法都不行：
 *  - SetCursor(NULL)：AWT 在 WM_SETCURSOR 里会把自己的光标塞回去，立刻被覆盖
 *  - ShowCursor(false)：显示计数确实压到 -1，但 AWT 刷新光标时又抬回来，
 *    实测 GetCursorInfo 仍报 CURSOR_SHOWING
 *
 * 改成给窗口换一个**完全透明**的光标：这是 AWT 自己的属性，AWT 会一直保持，
 * 不会被 WM_SETCURSOR 冲掉。恢复就是把窗口光标设回默认箭头。
 *
 * 每次调用都对比当前光标名，不一致才重设 —— 既避免每 400ms 刷一次属性，
 * 也能在 AWT 因为悬停把光标改回去之后自动纠正。
 *
 * AwtWindow 在 Main.kt 的 Window 作用域里 attach 一次即可。
 */
object CursorHider {

    private const val INVISIBLE_NAME = "invisible"

    @Volatile
    private var window: java.awt.Window? = null

    @Volatile
    private var invisible: java.awt.Cursor? = null

    /** 由 Main.kt 的 Window 作用域调用一次 */
    fun attach(w: java.awt.Window) {
        window = w
    }

    private fun invisibleCursor(): java.awt.Cursor {
        invisible?.let { return it }
        val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val c = java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), INVISIBLE_NAME)
        invisible = c
        return c
    }

    /** 隐藏光标 */
    fun hide() {
        runCatching {
            val w = window ?: return
            if (w.cursor?.name != INVISIBLE_NAME) w.cursor = invisibleCursor()
        }
    }

    /** 恢复默认箭头 */
    fun show() {
        runCatching {
            val w = window ?: return
            if (w.cursor !== java.awt.Cursor.getDefaultCursor()) w.cursor = java.awt.Cursor.getDefaultCursor()
        }
    }
}
