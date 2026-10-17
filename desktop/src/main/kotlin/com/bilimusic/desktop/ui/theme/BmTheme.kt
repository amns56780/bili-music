package com.bilimusic.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * bilimusic 设计系统（参考网易云音乐 PC 版歌单页的视觉语言，只取设计语言不取素材）。
 *
 * 三档背景 + 三档文字 + 一个品牌红，是这个界面全部的颜色层级。
 * 所有组件都从这里取色，不要再在组件里硬编码颜色。
 */
object BmColors {
    /** 页面主背景 */
    val bg = Color(0xFF18181B)

    /** 左侧歌单栏背景（比主区更深一档，形成层次） */
    val bgSide = Color(0xFF121214)

    /** 底部播放条背景（最深） */
    val bgPlayer = Color(0xFF0F0F11)

    /** 行 / 按钮悬浮 */
    val hover = Color(0xFF232328)

    /** 选中项 */
    val active = Color(0xFF2A2A31)

    /** 分隔线 / 描边 */
    val divider = Color(0xFF28282E)

    /** 主文字 */
    val text = Color(0xFFF2F2F4)

    /** 次要文字 */
    val text2 = Color(0xFFA6A6AE)

    /** 三级文字（序号、提示） */
    val text3 = Color(0xFF6B6B75)

    /** 品牌红（网易云同源） */
    val brand = Color(0xFFEC4141)

    val brandHover = Color(0xFFF05252)

    /** 当前播放行底色（品牌红 10%） */
    val brandSoft = Color(0x1AEC4141)

    /** 标签底色 */
    val tagBg = Color(0xFF222227)

    /** 弹窗 / 弹出面板底色 */
    val dialog = Color(0xFF202024)

    /** 进度条 / 音量条未播放部分 */
    val trackBar = Color(0xFF33333A)

    val white = Color.White

    /** 封面占位渐变色板：按歌单名 hash 取一组，保证同一歌单颜色稳定 */
    private val gradients = listOf(
        Color(0xFFF0A46B) to Color(0xFFC4553C),
        Color(0xFF3E7BD6) to Color(0xFF1E3D6E),
        Color(0xFF6BB6A0) to Color(0xFF2C6A5A),
        Color(0xFF9B7BD4) to Color(0xFF5A3E8E),
        Color(0xFFE4C56B) to Color(0xFFA67C2E),
        Color(0xFF5D8CFF) to Color(0xFF2B4C9E),
        Color(0xFFE06B8B) to Color(0xFF8E3350),
        Color(0xFF7FB069) to Color(0xFF3F6B33),
    )

    fun gradientFor(seed: String): Pair<Color, Color> {
        if (seed.isEmpty()) return gradients[0]
        return gradients[(seed.hashCode().let { if (it < 0) -it else it }) % gradients.size]
    }
}

/** 尺寸 token：整个界面的间距都从这里取，避免各处随手写 dp */
object BmDimens {
    val sidebarWidth = 240.dp
    val playerHeight = 76.dp

    val coverBig = 196.dp
    val coverSide = 36.dp
    val coverPlayer = 50.dp

    val rowHeight = 44.dp
    val tableHeaderHeight = 34.dp

    /** 主区内边距（左右） */
    val contentPaddingX = 34.dp

    val gapXs = 4.dp
    val gapSm = 8.dp
    val gapMd = 14.dp
    val gapLg = 24.dp

    val radiusCard = 14.dp
    val radiusCoverSm = 7.dp
    val radiusRow = 8.dp
    val radiusTag = 4.dp
}

/** 字排版：标题用 SemiBold 拉开层级，正文 13sp，次要信息 11~12sp */
object BmType {
    private val family = FontFamily.SansSerif

    val titleXl = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        color = BmColors.text,
    )

    val titleL = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        color = BmColors.text,
    )

    val titleM = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = BmColors.text,
    )

    val body = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = BmColors.text,
    )

    val bodySm = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = BmColors.text2,
    )

    val label = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = BmColors.text3,
    )

    val number = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = BmColors.text3,
    )
}

private val BmColorScheme = darkColorScheme(
    primary = BmColors.brand,
    onPrimary = BmColors.white,
    primaryContainer = BmColors.brand,
    onPrimaryContainer = BmColors.white,
    secondary = BmColors.text2,
    onSecondary = BmColors.bg,
    secondaryContainer = BmColors.active,
    onSecondaryContainer = BmColors.text,
    tertiary = BmColors.brand,
    onTertiary = BmColors.white,
    background = BmColors.bg,
    onBackground = BmColors.text,
    surface = BmColors.bg,
    onSurface = BmColors.text,
    surfaceVariant = BmColors.hover,
    onSurfaceVariant = BmColors.text2,
    outline = BmColors.divider,
    outlineVariant = BmColors.divider,
    error = BmColors.brand,
    onError = BmColors.white,
    scrim = Color(0xCC000000),
)

/** Material3 的排版也映射到我们的字号，保证弹窗 / 菜单和自绘组件观感一致 */
private val BmTypography = Typography(
    headlineMedium = BmType.titleXl,
    titleLarge = BmType.titleL,
    titleMedium = BmType.titleM,
    titleSmall = BmType.titleM,
    bodyLarge = BmType.body,
    bodyMedium = BmType.body,
    bodySmall = BmType.bodySm,
    labelLarge = BmType.bodySm,
    labelMedium = BmType.label,
    labelSmall = BmType.label,
)

@Composable
fun BmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BmColorScheme,
        typography = BmTypography,
        content = content,
    )
}
