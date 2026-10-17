package com.bilimusic.desktop.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.SwingUtilities

/**
 * 原生窗口移动（拖动标题栏用）。
 *
 * 为什么不用 AWT 的 window.location：那个调用在 EDT 上执行，会走 AWT 的
 * setLocation → 组件事件 → 重新布局一整条链路，和 Compose 的渲染抢同一个线程，
 * 拖动时表现为「跟得上但一顿一顿」。
 *
 * 这里改成：Java 只负责「读鼠标 + 算坐标」，真正的移动交给独立的 Win32 线程
 * 调 SetWindowPos，完全不碰 UI 线程，Compose 那边一次重绘都不用做。
 * 这是在没有原生 caption 拖动循环的前提下，能做到最接近网易云手感的方案。
 */
object NativeWindowMover {

    @Volatile
    private var hwnd: Pointer? = null

    val attached: Boolean get() = hwnd != null

    /** 找到窗口句柄（必须在窗口显示之后调用） */
    fun attach(title: String): Boolean = runCatching {
        hwnd = User32.INSTANCE.FindWindow(null, title)?.pointer
        hwnd != null
    }.getOrDefault(false)

    /** 直接移动窗口（不动尺寸、不抢焦点、不重绘客户区） */
    private fun moveTo(x: Int, y: Int) {
        val handle = hwnd ?: return
        runCatching {
            User32.INSTANCE.SetWindowPos(
                HWND(handle),
                null,
                x,
                y,
                0,
                0,
                SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_NOSENDCHANGING,
            )
        }
    }

    /**
     * 开始拖动：起一个守护线程按 4ms 跟随鼠标。
     * 松手后把最终位置同步回 AWT，保证 window.location 与实际一致。
     */
    fun startDrag(window: java.awt.Window) {
        if (!attached) return
        val startMouse = MouseInfo.getPointerInfo()?.location ?: return
        val startWindow = window.location
        var lastX = startWindow.x
        var lastY = startWindow.y
        Thread({
            while (isLeftMouseButtonDown()) {
                val now = MouseInfo.getPointerInfo()?.location
                if (now != null) {
                    lastX = startWindow.x + (now.x - startMouse.x)
                    lastY = startWindow.y + (now.y - startMouse.y)
                    moveTo(lastX, lastY)
                }
                Thread.sleep(DRAG_TICK_MS)
            }
            // 松手后再补一次，避免窗口停在鼠标后面
            val now = MouseInfo.getPointerInfo()?.location
            if (now != null) {
                lastX = startWindow.x + (now.x - startMouse.x)
                lastY = startWindow.y + (now.y - startMouse.y)
                moveTo(lastX, lastY)
            }
            val finalX = lastX
            val finalY = lastY
            SwingUtilities.invokeLater {
                runCatching { window.location = Point(finalX, finalY) }
            }
        }, "bm-window-drag").apply { isDaemon = true }.start()
    }
}

private const val SWP_NOSIZE = 0x0001
private const val SWP_NOZORDER = 0x0004
private const val SWP_NOACTIVATE = 0x0010
private const val SWP_NOSENDCHANGING = 0x0400

/** 跟随间隔：4ms ≈ 250Hz，比鼠标采样率还快，肉眼看不出滞后 */
private const val DRAG_TICK_MS = 4L
