package com.bilimusic.desktop.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Image
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.border.EmptyBorder

/**
 * 系统托盘 + 自绘弹出菜单。
 *
 * 为什么不用 AWT PopupMenu（交接文档坑 5 的最终解法）：
 * Windows 上它是原生 HMENU，由系统绘制，MenuItem.setFont() 会被忽略，
 * 中文会画成方框（实测踩到）。
 *
 * 为什么也没用 Swing 的 JPopupMenu：它依赖 invoker 窗口 + 系统菜单焦点机制，
 * 在托盘场景下 z-order 不可控（实测会出现菜单跑到应用窗口里、或关不掉的情况）。
 * 这里改成自己包一个置顶 JWindow，菜单项用 JLabel 画，关闭逻辑全部自己管：
 * 点菜单项 / 窗口失焦 / 再次右键，都会关掉。
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
    private var menuWindow: JWindow? = null

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
        hideMenu()
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
        trayIcon = null
    }

    private fun handleRightClick(e: MouseEvent) {
        if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
            showMenu()
        }
    }

    private fun showMenu() {
        val mouse = MouseInfo.getPointerInfo()?.location ?: return
        hideMenu()

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = MENU_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(MENU_BORDER),
                EmptyBorder(5, 0, 5, 0),
            )
        }

        fun item(text: String, action: () -> Unit) {
            val row = JLabel(text).apply {
                font = menuFont
                foreground = MENU_FG
                border = EmptyBorder(7, 18, 7, 36)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        isOpaque = true
                        background = MENU_SELECTION
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        isOpaque = false
                        repaint()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        hideMenu()
                        runCatching { action() }
                    }
                })
            }
            panel.add(row)
        }

        fun separator() {
            val line = JPanel().apply {
                background = MENU_BORDER
                isOpaque = true
                preferredSize = Dimension(1, 1)
            }
            val gap = JPanel(BorderLayout()).apply {
                background = MENU_BG
                isOpaque = true
                border = EmptyBorder(4, 0, 4, 0)
                preferredSize = Dimension(1, 9)
                maximumSize = Dimension(Int.MAX_VALUE, 9)
                add(line, BorderLayout.CENTER)
            }
            panel.add(gap)
        }

        item("显示窗口") { onShowWindow() }
        separator()
        item("播放 / 暂停") { onPlayPause() }
        item("上一首") { onPrevious() }
        item("下一首") { onNext() }
        separator()
        item("退出") {
            remove()
            onExit()
        }

        val window = JWindow()
        window.contentPane.add(panel)
        window.isAlwaysOnTop = true
        // preferredSize 偶尔会差几个像素把长菜单项截成省略号，这里补一点余量
        val preferred = panel.preferredSize
        window.setSize(preferred.width + 14, preferred.height)
        // 优先弹在鼠标右下方；贴边时向内收，避免跑出屏幕
        val screen = Toolkit.getDefaultToolkit().screenSize
        var x = mouse.x
        var y = mouse.y
        if (x + window.width > screen.width) x = screen.width - window.width
        if (y + window.height > screen.height) y = mouse.y - window.height
        window.setLocation(x, y)
        window.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) = Unit

            override fun windowLostFocus(e: WindowEvent) = hideMenu()
        })
        window.isVisible = true
        window.toFront()
        window.requestFocus()
        menuWindow = window
    }

    private fun hideMenu() {
        val window = menuWindow ?: return
        menuWindow = null
        runCatching { window.dispose() }
    }

    private companion object {
        val MENU_BG = Color(0x20, 0x20, 0x24)
        val MENU_FG = Color(0xE8, 0xE8, 0xEC)
        val MENU_BORDER = Color(0x3A, 0x3A, 0x42)
        val MENU_SELECTION = Color(0x2F, 0x2F, 0x36)
    }
}
