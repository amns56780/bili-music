package com.bilimusic.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmType

/**
 * 封面组件：有网络图就显示图，没有就用「按歌单名 hash 出来的渐变 + 首字」占位。
 *
 * 桌面端没有 Coil 这类图片库，侧栏里几十个歌单如果每个都发一次网络请求，
 * 打开界面就会卡一下；所以侧栏/歌单页的封面统一先走色块占位，成本为零。
 */
@Composable
fun BmCover(
    cover: ImageBitmap?,
    seed: String,
    size: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    val (c1, c2) = BmColors.gradientFor(seed)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = seed.take(1).ifBlank { "♪" },
                color = Color.White.copy(alpha = 0.92f),
                fontSize = (size.value * 0.36f).sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * 细条滑杆（进度 / 音量共用）。
 *
 * 关键点（对应交接文档坑 7）：拖动过程中只回调 [onChange] 让 UI 跟手，
 * 松手才回调 [onFinished] 去下发 VLC —— 每一帧都调 player 会让声音滞后于手。
 */
@Composable
fun BmSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    barHeight: Dp = 4.dp,
    hoverBarColor: Color = BmColors.brand,
) {
    var widthPx by remember { mutableStateOf(1f) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val clamped = value.coerceIn(0f, 1f)
    // ★ pointerInput(Unit) 的手势协程只会创建一次，直接引用参数会永远捕获
    //   「第一次组合时」的 lambda —— 那时往往还没开始播放（durationMs=0），
    //   于是松手回调里 if (durationMs > 0) 永远不成立，表现为「拖完弹回去」。
    //   rememberUpdatedState 保证协程里拿到的始终是最新回调。
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinished by rememberUpdatedState(onFinished)

    Box(
        modifier = modifier
            .height(height)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnChange((down.position.x / widthPx).coerceIn(0f, 1f))
                    down.consume()
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            currentOnChange((change.position.x / widthPx).coerceIn(0f, 1f))
                            change.consume()
                        } else {
                            pressed = false
                        }
                        if (event.changes.all { !it.pressed }) pressed = false
                    }
                    currentOnFinished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val h = barHeight.toPx()
            val played = size.width * clamped
            drawRoundRect(
                color = BmColors.trackBar,
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f),
            )
            if (played > 0f) {
                drawRoundRect(
                    color = hoverBarColor,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size(played, h),
                    cornerRadius = CornerRadius(h / 2f),
                )
            }
            if (hovered) {
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(played.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx()), cy),
                )
            }
        }
    }
}

/** 图标按钮：hover 有圆形底色，指针变手型（Compacc 默认 IconButton 没有 hover 反馈，桌面端很闷） */
@Composable
fun BmIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    tint: Color = BmColors.text2,
    hoverBg: Color = BmColors.hover,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (hovered && enabled) hoverBg else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 主按钮：品牌红胶囊（「播放全部」） */
@Composable
fun BmPrimaryButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (hovered) BmColors.brandHover else BmColors.brand)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text = text, color = Color.White, style = BmType.titleM)
    }
}

/** 次要按钮：描边胶囊（「收藏」「下载」） */
@Composable
fun BmGhostButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (hovered) BmColors.hover else Color.Transparent)
            .border(1.dp, BmColors.divider, RoundedCornerShape(17.dp))
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = BmColors.text2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, color = if (enabled) BmColors.text2 else BmColors.text3, style = BmType.body)
    }
}

/** 圆形描边按钮（「⋯」更多） */
@Composable
fun BmRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (hovered) BmColors.hover else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = BmColors.text2, modifier = Modifier.size(16.dp))
    }
}

/** 分隔线 */
@Composable
fun BmDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(1.dp).background(BmColors.divider))
}

/**
 * 弹出菜单（替代 Material3 的 DropdownMenu）。
 *
 * 为什么自己写：DropdownMenu 在 Row 里定位会飘到父布局起点（实测多选条上的「复制到」
 * 菜单跑到了左边），而且样式跟深色设计不统一。这里用 Popup 钉在触发按钮的 Box 上，
 * 位置完全可控。
 *
 * 用法：把触发按钮和本组件放进同一个 Box，[expanded] 由按钮控制。
 */
@Composable
fun BmPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    alignEnd: Boolean = false,
    width: Dp = 190.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    Popup(
        alignment = if (alignEnd) Alignment.BottomEnd else Alignment.BottomStart,
        offset = with(density) { IntOffset(0, 6.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF202024))
                .padding(vertical = 6.dp),
        ) {
            content()
        }
    }
}

/** 菜单项：hover 高亮，[danger] 用于删除这类不可逆操作 */
@Composable
fun BmMenuItem(
    text: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered && enabled) BmColors.hover else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = BmType.body,
            color = when {
                !enabled -> BmColors.text3.copy(alpha = 0.5f)
                danger -> BmColors.brand
                else -> BmColors.text
            },
        )
    }
}

/** 菜单里的分隔线 */
@Composable
fun BmMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 10.dp)
            .height(1.dp)
            .background(BmColors.divider),
    )
}
