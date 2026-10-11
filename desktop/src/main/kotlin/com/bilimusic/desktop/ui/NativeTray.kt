package com.bilimusic.desktop.ui

import java.awt.Font
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * 原生 AWT 托盘菜单。
 *
 * 为什么不用 Compose 的 `Tray(menu = ...)`：
 * 它的菜单底层是 AWT 原生菜单，字体不受 Compose/Skia 控制，`UIManager` 也管不到，
 * 只有 `MenuItem.setFont()` 生效 —— 所以这里直接用 AWT 的 TrayIcon + PopupMenu。
 *
 * 关于中文：字体探测已确认系统字体没问题（Microsoft YaHei UI 可用、canDisplayCJK=true），
 * 乱码根源是 file.encoding(UTF-8) 与 sun.jnu.encoding(GBK) 不一致，启动参数已改为
 * -Dfile.encoding=GBK 对齐两者。标签采用「中文 (English)」形式：万一某些环境下中文
 * 仍不正常，英文部分依旧可读，不至于出现整条菜单看不懂的情况。
 */
class NativeTray(
    private val iconPng: ByteArray,
    private val tooltip: String,
    private val onShowWindow: () -> Unit,
    private val onPlayPause: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onExit: () -> Unit,
) {

    private var trayIcon: TrayIcon? = null

    private val cjkFont: Font = listOf(
        "Microsoft YaHei UI",
        "Microsoft YaHei",
        "SimHei",
        "Dialog",
    ).firstNotNullOfOrNull { name -> runCatching { Font(name, Font.PLAIN, 12) }.getOrNull() }
        ?: Font(Font.DIALOG, Font.PLAIN, 12)

    fun install() {
        if (trayIcon != null) return
        if (!SystemTray.isSupported()) return
        val image: Image = runCatching { ImageIO.read(ByteArrayInputStream(iconPng)) }.getOrNull() ?: return

        val popup = PopupMenu()

        fun add(label: String, action: () -> Unit) {
            val item = MenuItem(label)
            // 原生菜单项必须逐项指定字体，否则中文可能拿不到字形
            runCatching { item.font = cjkFont }
            item.addActionListener { runCatching { action() } }
            popup.add(item)
        }

        add("显示窗口 (Show window)", onShowWindow)
        popup.addSeparator()
        add("播放 / 暂停 (Play / Pause)", onPlayPause)
        add("上一首 (Previous)", onPrevious)
        add("下一首 (Next)", onNext)
        popup.addSeparator()
        add("退出 (Quit)", onExit)

        val icon = TrayIcon(image, tooltip, popup).apply {
            isImageAutoSize = true
            addActionListener { runCatching { onShowWindow() } }
        }
        runCatching {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    fun remove() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
        trayIcon = null
    }
}
