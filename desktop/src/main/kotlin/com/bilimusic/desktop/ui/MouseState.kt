package com.bilimusic.desktop.ui

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

/** 鼠标按键状态（拖动窗口时用来判断左键是否已松开，避免依赖事件流） */
internal interface MouseKeyApi : StdCallLibrary {
    fun GetAsyncKeyState(vKey: Int): Short
}

internal val mouseKeys: MouseKeyApi = Native.load("user32", MouseKeyApi::class.java)

internal fun isLeftMouseButtonDown(): Boolean =
    (mouseKeys.GetAsyncKeyState(VK_LBUTTON).toInt() and 0x8000) != 0

private const val VK_LBUTTON = 0x01
