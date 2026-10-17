package com.bilimusic.desktop.ui

import java.awt.Color
import java.awt.Font
import java.awt.Image
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * 系统托盘（Swing 自绘菜单）。
 *
 * 为什么不用 AWT 的 PopupMenu / MenuItem（交接文档坑 5 的最终解法）：
 * Windows 上的 AWT 菜单是原生 HMENU，由系统绘制，MenuItem.setFont() 会被忽略，
 * 于是永远用系统默认菜单字体 —— 部分环境下中文会画成方框（实测踩到）。
 *
 * 改用 Swing 的 JPopupMenu：Java2D 自绘，字体 / 颜色完全可控，
 * 中文渲染与主界面一致。代价是需要一个 1x1 的隐藏窗口当弹出宿主。
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
    private var host: JWindow? = null
    private val popup = JPopupMenu()

    private val menuFont: Font = run {
        val families = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        val wanted = listOf("Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "Noto Sans CJK SC")
        val family = wanted.firstOrNull { name -> families.any { it.equals(name, ignoreCase = true) } }
            ?: Font.DIALOG
        Font(family, Font.PLAIN, 12)
    }

    fun install() {
        if (trayIcon != null) return
        if (!SystemTray.isSupported()) return
        val image: Image = runCatching { ImageIO.read(ByteArrayInputStream(iconPng)) }.getOrNull() ?: return


        applyMenuTheme()
        buildMenu()

        val icon = TrayIcon(image, tooltip).apply {
            isImageAutoSize = true
            addActionListener { runCatching { onShowWindow() } }
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = handleRightClick(e)
                override fun mouseReleased(e: MouseEvent) = handleRightClick(e)
            })
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
        host?.dispose()
        host = null
    }

    private fun handleRightClick(e: MouseEvent) {
        if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
            showMenu()
        }
    }

    private fun showMenu() {
        val location = MouseInfo.getPointerInfo()?.location ?: return
        val invoker = host ?: JWindow().also { host = it }
        invoker.setSize(1, 1)
        invoker.setLocation(location)
        invoker.isAlwaysOnTop = true
        invoker.isVisible = true
        invoker.toFront()
        invoker.requestFocus()
        // 注意：show 之后不能立刻隐藏 invoker，否则 JPopupMenu 会跟着关闭。
        // 等菜单关闭时再由 PopupMenuListener 把宿主收起来。
        popup.show(invoker, 0, 0)
    }

    private fun buildMenu() {
        fun item(text: String, action: () -> Unit) {
            val menuItem = JMenuItem(text).apply {
                font = menuFont
                isOpaque = true
                background = MENU_BG
                foreground = MENU_FG
                border = EmptyBorder(7, 14, 7, 26)
                addActionListener {
                    runCatching { popup.isVisible = false }
                    runCatching { action() }
                }
            }
            popup.add(menuItem)
        }

        popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) = Unit

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {
                host?.isVisible = false
            }

            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {
                host?.isVisible = false
            }
        })

        item("显示窗口") { onShowWindow() }
        addSeparator()
        item("播放 / 暂停") { onPlayPause() }
        item("上一首") { onPrevious() }
        item("下一首") { onNext() }
        addSeparator()
        item("退出") { onExit() }
    }

    /** 自绘分隔线：Metal 的 JSeparator 画出来是一条亮白线，跟深色菜单不搭 */
    private fun addSeparator() {
        val outer = javax.swing.JPanel(java.awt.BorderLayout()).apply {
            isOpaque = true
            background = MENU_BG
            border = EmptyBorder(4, 0, 4, 0)
            preferredSize = java.awt.Dimension(1, 9)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 9)
        }
        val line = javax.swing.JPanel().apply {
            isOpaque = true
            background = MENU_BORDER
            preferredSize = java.awt.Dimension(1, 1)
        }
        outer.add(line, java.awt.BorderLayout.CENTER)
        popup.add(outer)
    }

    private fun applyMenuTheme() {
        UIManager.put("PopupMenu.background", MENU_BG)
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(MENU_BORDER, 1, true))
        UIManager.put("MenuItem.font", menuFont)
        UIManager.put("MenuItem.background", MENU_BG)
        UIManager.put("MenuItem.foreground", MENU_FG)
        UIManager.put("MenuItem.selectionBackground", MENU_SELECTION)
        UIManager.put("MenuItem.selectionForeground", Color.WHITE)
        UIManager.put("MenuItem.borderPainted", false)
        UIManager.put("Separator.background", MENU_BG)
        UIManager.put("Separator.foreground", MENU_BORDER)
        popup.background = MENU_BG
        popup.border = BorderFactory.createLineBorder(MENU_BORDER, 1, true)
    }

    private companion object {
        val MENU_BG = Color(0x20, 0x20, 0x24)
        val MENU_FG = Color(0xE8, 0xE8, 0xEC)
        val MENU_BORDER = Color(0x3A, 0x3A, 0x42)
        val MENU_SELECTION = Color(0x2F, 0x2F, 0x36)
    }
}
