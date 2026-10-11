package com.bilimusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Window
import javax.swing.JFrame

/**
 * 自绘标题栏。
 *
 * 系统标题栏是浅蓝灰色，跟应用的深色界面放一起很割裂，所以窗口用 undecorated
 * （见 Main.kt），这里补一条与应用同色的标题栏。
 *
 * 拖动**不在这里实现**：调用方用 Compose 官方的 `WindowDraggableArea` 把这个标题栏
 * 包起来，拖动完全交给系统窗口管理器（等价于 Electron 的 `-webkit-app-region: drag`）。
 * 之前自己用 Compose 手势逐帧 setLocation，主线程既处理输入又要重绘，结果就是卡顿。
 */
@Composable
fun AppTitleBar(
    window: Window,
    title: String,
    icon: Painter?,
    onClose: () -> Unit,
) {
    val frame = window as? JFrame
    var maximized by remember { mutableStateOf(frame?.extendedState == JFrame.MAXIMIZED_BOTH) }
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant

    fun toggleMaximize() {
        if (frame == null) return
        frame.extendedState =
            if (frame.extendedState == JFrame.MAXIMIZED_BOTH) JFrame.NORMAL else JFrame.MAXIMIZED_BOTH
        maximized = frame.extendedState == JFrame.MAXIMIZED_BOTH
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { toggleMaximize() })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            if (icon != null) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.Unspecified,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(title, style = MaterialTheme.typography.labelLarge, color = onSurface)
            Spacer(Modifier.weight(1f))
            TitleBarButton(Icons.Outlined.Remove, onSurface, "最小化") {
                frame?.extendedState = JFrame.ICONIFIED
            }
            TitleBarButton(
                if (maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare,
                onSurface,
                if (maximized) "还原" else "最大化",
                onClick = { toggleMaximize() },
            )
            TitleBarButton(Icons.Outlined.Close, onSurface, "关闭", danger = true, onClick = onClose)
        }
        // 细到 1dp 的分隔线：有层次但不抢眼
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun TitleBarButton(
    icon: ImageVector,
    tint: Color,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        danger && hovered -> Color(0xFFC42B1C)
        hovered -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val foreground = if (danger && hovered) Color.White else tint
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(38.dp)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(15.dp), tint = foreground)
    }
}
