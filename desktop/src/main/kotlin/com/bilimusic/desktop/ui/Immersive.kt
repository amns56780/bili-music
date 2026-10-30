package com.bilimusic.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.player.PlaybackUiState
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 沉浸模式的运行时状态（由 Main 持有，可持久化到 settings） */
class ImmersiveState {
    var open by mutableStateOf(false)
    var bgMode by mutableStateOf(ImmersiveBackground.WALLPAPER)
    var customBgPath by mutableStateOf<String?>(null)
    var showWave by mutableStateOf(true)
}

/** 沉浸模式的背景来源 */
enum class ImmersiveBackground(val label: String) {
    WALLPAPER("桌面壁纸"),
    IMAGE("自定义图片"),
    SOLID("纯色"),
}

/**
 * 沉浸听歌模式：点底部播放条的封面，自下而上整屏弹出。
 *
 * 只保留最低限度的信息：歌名 / 艺术家 / 播放进度 / 进入沉浸模式的时间与已听时长，
 * 外加可选的一排随音乐律动的线条。背景可以是桌面壁纸（模糊处理）、
 * 自己选的图片，或者纯色。
 */
@Composable
fun ImmersivePlayer(
    visible: Boolean,
    state: PlaybackUiState,
    cover: ImageBitmap?,
    controller: PlaybackController,
    onClose: () -> Unit,
    bgMode: ImmersiveBackground,
    customBgPath: String?,
    onBgModeChange: (ImmersiveBackground) -> Unit,
    onPickImage: () -> Unit,
    showWave: Boolean,
    onToggleWave: () -> Unit,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        // 沉浸模式期间才采集频谱（避免平时白占 CPU）
        androidx.compose.runtime.DisposableEffect(Unit) {
            com.bilimusic.desktop.player.AudioSpectrum.start()
            onDispose { com.bilimusic.desktop.player.AudioSpectrum.stop() }
        }
        val blurred = rememberBlurredBackground(bgMode, customBgPath)
        val enteredAt = remember { System.currentTimeMillis() }
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(visible) {
            while (visible) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }
        var showBgMenu by remember { mutableStateOf(false) }
        var showVolume by remember { mutableStateOf(false) }
        // 进度条拖动状态：拖动时只改 draft，松手才真正 seek（避免每帧跨语言调用积压）
        var seekDragging by remember { mutableStateOf(false) }
        var seekDraft by remember { mutableFloatStateOf(0f) }
        // 切换播放模式后短暂显示模式名
        var modeTip by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state.mode) {
            modeTip = state.mode.displayName
            kotlinx.coroutines.delay(2000)
            modeTip = null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0C))
                // 滚轮微调音量（±1%）：沉浸模式里没有音量条可见，滚动即可调
                .volumeWheel { step ->
                    val v = (controller.state.value.volume + step).coerceIn(0, 100)
                    controller.setVolume(v, persist = true)
                },
        ) {
            // ---------------- 点击拦截层 ----------------
            // 铺满整屏、吞掉所有落空的点击。它声明在内容之前（z 序更低），
            // 所以按钮等控件仍然优先命中；只有空白区域的点击会被这一层吃掉，
            // 不会再"穿透"到别的控件上导致误切歌。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) {
                        // 空白处点击：关闭可能展开的背景菜单，别的什么也不做
                        showBgMenu = false
                    },
            )

            // ---------------- 背景 ----------------
            when (bgMode) {
                ImmersiveBackground.SOLID -> {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color(0xFF1B1B22), Color(0xFF0A0A0C))),
                        ),
                    )
                }

                else -> {
                    if (blurred != null) {
                        androidx.compose.foundation.Image(
                            bitmap = blurred,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            // 压暗 + 顶部/底部渐变，保证文字可读
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0x99000000), Color(0x44000000), Color(0xC8000000)),
                    ),
                ),
            )

            // ---------------- 左上角：时间 / 日期 / 星期 ----------------
            Column(modifier = Modifier.align(Alignment.TopStart).padding(28.dp)) {
                Text(
                    text = formatClock(now),
                    style = BmType.number.copy(fontSize = 42.sp),
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDateWithWeek(now),
                    style = BmType.bodySm,
                    color = Color(0x99FFFFFF),
                )
            }

            // ---------------- 右上角操作 ----------------
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 全屏切换
                ImmersiveButton(
                    icon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                    size = 40.dp,
                    iconSize = 20.dp,
                    background = Color(0x33000000),
                    onClick = onToggleFullscreen,
                )
                // 播放模式切换（顺序 / 列表循环 / 单曲循环 / 随机）
                ImmersiveButton(
                    icon = immersiveModeIcon(state.mode),
                    contentDescription = "播放模式：" + state.mode.displayName,
                    size = 40.dp,
                    iconSize = 20.dp,
                    background = Color(0x33000000),
                    onClick = { controller.cycleMode() },
                )
                // 音量：点击展开滑块
                ImmersiveButton(
                    icon = immersiveVolumeIcon(state.volume),
                    contentDescription = "音量",
                    size = 40.dp,
                    iconSize = 20.dp,
                    background = Color(0x33000000),
                    onClick = { showVolume = !showVolume },
                )
                ImmersiveButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "背景设置",
                    size = 40.dp,
                    iconSize = 20.dp,
                    background = Color(0x33000000),
                    onClick = { showBgMenu = !showBgMenu },
                )
                ImmersiveButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "退出沉浸模式",
                    size = 40.dp,
                    iconSize = 20.dp,
                    background = Color(0x33000000),
                    onClick = onClose,
                )
            }

            // 音量弹层
            if (showVolume) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 72.dp, end = 20.dp)
                        .width(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xEE1C1C22))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = immersiveVolumeIcon(state.volume),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    BmSlider(
                        value = state.volume / 100f,
                        onChange = { v ->
                            controller.setVolume((v * 100f).toInt().coerceIn(0, 100), persist = false)
                        },
                        onFinished = { controller.persistVolume() },
                        modifier = Modifier.weight(1f),
                        hoverBarColor = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = state.volume.toString(),
                        style = BmType.number,
                        color = Color(0xCCFFFFFF),
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }

            // 背景设置弹层
            if (showBgMenu) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 72.dp, end = 20.dp)
                        .width(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xEE1C1C22))
                        .padding(vertical = 6.dp),
                ) {
                    ImmersiveBackground.entries.forEach { mode ->
                        TextRow(
                            label = mode.label,
                            selected = mode == bgMode,
                            onClick = {
                                onBgModeChange(mode)
                                if (mode == ImmersiveBackground.IMAGE) onPickImage()
                                showBgMenu = false
                            },
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                    TextRow(
                        label = if (showWave) "隐藏律动线条" else "显示律动线条",
                        selected = false,
                        onClick = {
                            onToggleWave()
                            showBgMenu = false
                        },
                    )
                }
            }

            // ---------------- 主内容 ----------------
            val current = state.current
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))

                // 大封面
                BmCover(
                    cover = cover,
                    seed = current?.title ?: "♪",
                    size = 260.dp,
                    radius = 20.dp,
                )

                Spacer(Modifier.height(34.dp))

                Text(
                    text = current?.title ?: "还没有在播放",
                    style = BmType.titleL.copy(fontSize = 30.sp),
                    color = Color.White,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = current?.artist?.ifBlank { "未知UP主" } ?: "",
                    style = BmType.body,
                    color = Color(0xB3FFFFFF),
                )

                Spacer(Modifier.height(30.dp))

                // 进度（可拖动：拖动时用本地 draft，松手才 seek，和主界面播放条同一套交互）
                val duration = state.durationMs.coerceAtLeast(1L)
                val ratio = if (seekDragging) seekDraft else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                Column(modifier = Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BmSlider(
                        value = ratio,
                        onChange = { v ->
                            seekDragging = true
                            seekDraft = v
                        },
                        onFinished = {
                            if (state.durationMs > 0) controller.seekTo((seekDraft * state.durationMs).toLong())
                            seekDragging = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 18.dp,
                        barHeight = 4.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = if (seekDragging) {
                                formatMs((seekDraft * duration).toLong()) + " / " + formatMs(state.durationMs)
                            } else {
                                formatMs(state.positionMs) + " / " + formatMs(state.durationMs)
                            },
                            style = BmType.number,
                            color = Color(0xCCFFFFFF),
                        )
                        Text(
                            text = formatClock(enteredAt) + " 进入 · 已听 " +
                                formatElapsed(now - enteredAt),
                            style = BmType.number,
                            color = Color(0x99FFFFFF),
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                // 播放控制：每个按钮独立固定尺寸，点击范围严格等于按钮本身
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImmersiveButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                    ) { controller.previous() }
                    Spacer(Modifier.width(16.dp))
                    ImmersiveButton(
                        icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "播放",
                        size = 58.dp,
                        iconSize = 30.dp,
                        background = Color(0xFFEC4141),
                    ) { controller.togglePlayPause() }
                    Spacer(Modifier.width(16.dp))
                    ImmersiveButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                    ) { controller.next() }
                }

                // 模式切换提示：固定高度占位（无提示时留空），避免显示/隐藏时把整个界面顶动
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = modeTip ?: "",
                        style = BmType.label,
                        color = Color(0x99FFFFFF),
                    )
                }

                Spacer(Modifier.weight(1f))

                // 底部律动线条
                if (showWave) {
                    WaveLine(
                        playing = state.playing,
                        modifier = Modifier.fillMaxWidth(0.7f).height(90.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * 固定尺寸的圆形按钮。
 *
 * 用 pointerInput + detectTapGestures 而不是 clickable：前者只按 Box 的实际尺寸做
 * 命中测试，后者在某些父布局下会被 Compose 的"最小触摸目标"放大，导致点击范围
 * 超出按钮（沉浸模式里表现为点空白也切歌）。
 */
@Composable
private fun ImmersiveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
    background: Color = Color.Transparent,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 悬停反馈：透明底的按钮浮一层淡淡的白色；有底色（品牌红）的按钮稍微压暗一点
    val bg = when {
        background == Color.Transparent -> if (hovered) Color(0x22FFFFFF) else Color.Transparent
        hovered -> background.copy(alpha = 0.86f)
        else -> background
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .hoverable(interaction)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (hovered) Color.White else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun TextRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BmType.body,
            color = if (selected) BmColors.brand else Color(0xE6FFFFFF),
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(BmColors.brand))
        }
    }
}

/**
 * 真实的音频频谱（WASAPI 环回采集 + FFT，见 AudioSpectrum）。
 *
 * 32 个对数频段线性插值成 56 根柱子，每根的高度就是当前时刻该频段的能量。
 * 播放/暂停整体幅度用 animateFloatAsState 过渡，避免一暂停就瞬间掉平。
 */
@Composable
private fun WaveLine(playing: Boolean, modifier: Modifier = Modifier) {
    val bars = 56
    val spectrum by com.bilimusic.desktop.player.AudioSpectrum.bands.collectAsState()
    val level by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "waveLevel",
    )
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val bandCount = com.bilimusic.desktop.player.AudioSpectrum.BANDS
        for (i in 0 until bars) {
            // 32 段 → 56 根，线性插值
            val srcPos = i.toFloat() / (bars - 1) * (bandCount - 1)
            val i0 = srcPos.toInt().coerceIn(0, bandCount - 1)
            val i1 = (i0 + 1).coerceAtMost(bandCount - 1)
            val frac = srcPos - i0
            val energy = (spectrum.getOrElse(i0) { 0f } * (1f - frac) +
                spectrum.getOrElse(i1) { 0f } * frac).coerceIn(0f, 1f)
            // 静止时保留一条很矮的基线，运动时叠加 level 过渡
            val amplitude = 0.06f + 0.94f * energy * level
            val h = (size.height * amplitude).coerceAtLeast(1f)
            val x = i * (w + gap)
            drawRoundRect(
                color = Color(0xFFEC4141).copy(alpha = 0.30f + 0.60f * amplitude),
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
            )
        }
    }
}

// ---------------- 工具 ----------------

private fun immersiveVolumeIcon(volume: Int) = when {
    volume <= 0 -> Icons.Filled.VolumeOff
    volume < 50 -> Icons.Filled.VolumeDown
    else -> Icons.Filled.VolumeUp
}

private fun formatDateWithWeek(ms: Long): String =
    java.text.SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(ms))

private fun immersiveModeIcon(mode: com.bilimusic.app.domain.model.PlayMode) =
    when (mode) {
        com.bilimusic.app.domain.model.PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Outlined.PlaylistPlay
        com.bilimusic.app.domain.model.PlayMode.REPEAT_ALL -> Icons.Outlined.Repeat
        com.bilimusic.app.domain.model.PlayMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
        com.bilimusic.app.domain.model.PlayMode.SHUFFLE -> Icons.Outlined.Shuffle
    }

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}

private fun formatClock(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total < 60) total.toString() + " 秒" else (total / 60).toString() + " 分钟"
}

/** 读注册表里的桌面壁纸路径 */
private fun readWallpaperPath(): String? = runCatching {
    val proc = ProcessBuilder("reg", "query", "HKCU\\Control Panel\\Desktop", "/v", "WallPaper")
        .redirectErrorStream(true)
        .start()
    val text = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    Regex("WallPaper\\s+REG_SZ\\s+(.+)").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

/**
 * 高斯模糊背景（Skia 的 ImageFilter.makeBlur）。
 *
 * 之前的做法是缩到 48x48 再由 Compose 放大 —— 那是"马赛克式的糊"，不是毛玻璃。
 * 这里直接让 Skia 做真正的高斯卷积：先按目标宽度缩放（控制耗时），
 * 再用 sigma≈60 的模糊滤镜绘制到离屏 Surface 上。
 */
private fun gaussianBlur(path: String, sigma: Float = 38f, maxWidth: Int = 1600): ImageBitmap? = runCatching {
    val bytes = File(path).readBytes()
    val skImage = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    if (skImage.width <= 0 || skImage.height <= 0) return@runCatching null
    val w = minOf(maxWidth, skImage.width)
    val h = (skImage.height.toFloat() * w / skImage.width).toInt().coerceAtLeast(1)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    val paint = org.jetbrains.skia.Paint().apply {
        imageFilter = org.jetbrains.skia.ImageFilter.makeBlur(
            sigma,
            sigma,
            org.jetbrains.skia.FilterTileMode.CLAMP,
            null,
        )
    }
    surface.canvas.drawImageRect(
        skImage,
        org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
        paint,
    )
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()

@Composable
private fun rememberBlurredBackground(mode: ImmersiveBackground, customPath: String?): ImageBitmap? {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mode, customPath) {
        bitmap = withContext(Dispatchers.IO) {
            when (mode) {
                ImmersiveBackground.SOLID -> null
                ImmersiveBackground.IMAGE -> customPath?.let { gaussianBlur(it) }
                ImmersiveBackground.WALLPAPER -> gaussianBlur(readWallpaperPath() ?: return@withContext null)
            }
        }
    }
    return bitmap
}

