package com.bilimusic.desktop.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

private interface DwmApiEx : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: HWND, attr: Int, value: Pointer, size: Int): Int
}

private val dwmApi: DwmApiEx = Native.load("dwmapi", DwmApiEx::class.java, W32APIOptions.DEFAULT_OPTIONS)

/**
 * 把系统标题栏切成深色（Windows 10 1809+ 的 DWMWA_USE_IMMERSIVE_DARK_MODE）。
 *
 * 为什么保留系统标题栏、不再自绘：
 * 窗口拖动要顺滑，就必须由系统窗口管理器来做。DeepSeek Harness 在 Windows 上用的是
 * Electron 的 titleBarStyle=hidden + titleBarOverlay —— 本质同样是"保留系统窗口框架、
 * 由 DWM 负责拖动"，自己只画标题栏里的内容。
 *
 * JVM/Compose 这边做不到"内容延伸到 caption 区再交给系统拖"，硬要用 Compose 手势
 * 逐帧 setLocation 一定会卡：主线程既要处理输入事件，又要重绘整个界面。
 * 所以等价取舍是：保留原生边框（拖动因此是系统级的，天然顺滑，并白拿双击最大化、
 * Aero 贴边、摇动等系统行为），只把标题栏颜色改成深色，让它跟应用的深色界面协调。
 */
fun applyWindowsDarkTitleBar(window: Window, title: String) {
    runCatching {
        val hwnd = User32.INSTANCE.FindWindow(null, title) ?: return
        val on = IntByReference(1)
        // 20 = DWMWA_USE_IMMERSIVE_DARK_MODE（Win10 1809 起）；更旧的系统用 19
        if (dwmApi.DwmSetWindowAttribute(hwnd, 20, on.pointer, 4) != 0) {
            dwmApi.DwmSetWindowAttribute(hwnd, 19, on.pointer, 4)
        }
    }
}
