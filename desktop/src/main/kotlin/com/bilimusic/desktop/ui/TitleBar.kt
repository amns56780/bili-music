package com.bilimusic.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmType

/** 自绘标题栏高度（dp） */
private val TITLE_BAR_HEIGHT = 36.dp

/** 单个窗口按钮宽度（dp） */
private val BUTTON_WIDTH = 46.dp

private val titleBarBg = Color(0xFF0C0C0E)
private val windowBtnHover = Color(0xFF2A2A31)
private val closeBtnHover = Color(0xFFC42B1C)

private enum class WindowButtonKind { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

/**
 * 自绘标题栏。
 *
 * 拖动在 Compose 里按「指针事件位移」搬窗口（见下方 pointerInput）。
 * 试过两条原生路线（子类化 WndProc 返回 HTCAPTION、ReleaseCapture + WM_NCLBUTTONDOWN /
 * DefWindowProc），在这套 AWT 窗口上一个都不生效：前者回调不进 JVM，后者被 SunAwtFrame
 * 吞掉。最终用事件增量位移，避免了交接文档坑 8 里「用窗口坐标反推增量」造成的正反馈震荡。
 */
@Composable
fun AppTitleBar(
    window: java.awt.Window,
    title: String,
    icon: Painter?,
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TITLE_BAR_HEIGHT)
            .background(titleBarBg)
            .pointerInput(window, maximized) {
                awaitEachGesture {
                    // 只接管「没被按钮消费」的按下事件 —— 也就是标题栏空白处
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (down.type != PointerType.Mouse) return@awaitEachGesture
                    // 右侧窗口按钮区不参与拖动（避免抢掉按钮的点击）
                    val buttonsWidthPx = 46.dp.toPx() * 3
                    if (down.position.x > size.width - buttonsWidthPx) return@awaitEachGesture
                    down.consume()
                    if (maximized) return@awaitEachGesture
                    // 拖动窗口的算法要点（踩过两次坑）：
                    // 1) 不能用「指针事件相对坐标的增量」——窗口一移动，鼠标相对窗口的位置也跟着变，
                    //    形成正反馈，表现为鬼畜抖动（交接文档坑 8）。
                    // 2) 改用鼠标的「屏幕绝对坐标」：按下时记录鼠标屏幕位置和窗口位置，
                    //    之后每一 tick 直接算「窗口位置 = 起点 + (当前鼠标 - 起点鼠标)」。
                    //    这是绝对计算，没有反馈环，所以不会抖。
                    // 3) 跟随用定时轮询而不是纯事件：窗口完美跟随时鼠标相对窗口不再变化，
                    //    Compose 就收不到 move 事件了，必须自己按固定频率跟一次。
                    // 移动交给原生线程（不占 UI 线程），这里只负责触发
                    NativeWindowMover.startDrag(window)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        if (icon != null) {
            Image(painter = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = title, style = BmType.label, color = BmColors.text2)
        Spacer(Modifier.weight(1f))
        TitleBarButton(WindowButtonKind.MINIMIZE, onMinimize)
        TitleBarButton(
            if (maximized) WindowButtonKind.RESTORE else WindowButtonKind.MAXIMIZE,
            onToggleMaximize,
        )
        TitleBarButton(WindowButtonKind.CLOSE, onClose)
    }
}

@Composable
private fun TitleBarButton(kind: WindowButtonKind, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isClose = kind == WindowButtonKind.CLOSE
    Box(
        modifier = Modifier
            .width(BUTTON_WIDTH)
            .height(TITLE_BAR_HEIGHT)
            .background(
                when {
                    hovered && isClose -> closeBtnHover
                    hovered -> windowBtnHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (hovered && isClose) Color.White else Color(0xFFD0D0D6)
        Canvas(modifier = Modifier.size(10.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.dp.toPx()
            when (kind) {
                WindowButtonKind.MINIMIZE -> drawLine(
                    color = tint,
                    start = Offset(0f, h / 2f),
                    end = Offset(w, h / 2f),
                    strokeWidth = stroke,
                )

                WindowButtonKind.MAXIMIZE -> drawRect(
                    color = tint,
                    topLeft = Offset(0.5f, 0.5f),
                    size = Size(w - 1f, h - 1f),
                    style = Stroke(width = stroke),
                )

                WindowButtonKind.RESTORE -> {
                    drawRect(
                        color = tint,
                        topLeft = Offset(0f, 2.5f),
                        size = Size(w - 2.5f, h - 2.5f),
                        style = Stroke(width = stroke),
                    )
                    drawLine(color = tint, start = Offset(2.5f, 0f), end = Offset(w, 0f), strokeWidth = stroke)
                    drawLine(color = tint, start = Offset(w, 0f), end = Offset(w, h - 2.5f), strokeWidth = stroke)
                }

                WindowButtonKind.CLOSE -> {
                    drawLine(color = tint, start = Offset(0f, 0f), end = Offset(w, h), strokeWidth = stroke)
                    drawLine(color = tint, start = Offset(w, 0f), end = Offset(0f, h), strokeWidth = stroke)
                }
            }
        }
    }
}


