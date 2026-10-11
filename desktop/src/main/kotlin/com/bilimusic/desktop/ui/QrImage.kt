package com.bilimusic.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * 把一段文本画成二维码位图（登录页用）。
 * ZXing 是纯 JVM 库，桌面端不需要任何 Android 依赖。
 */
fun encodeQrToImageBitmap(content: String, sizePx: Int = 320): ImageBitmap? = runCatching {
    val hints: Map<EncodeHintType, Any> = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    // Skia 的 N32 在 little-endian 上是 BGRA 字节序
    val bytes = ByteArray(sizePx * sizePx * 4)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            val dark = matrix.get(x, y)
            val offset = (y * sizePx + x) * 4
            val value: Byte = if (dark) 0x00 else 0xFF.toByte()
            bytes[offset] = value     // B
            bytes[offset + 1] = value // G
            bytes[offset + 2] = value // R
            bytes[offset + 3] = 0xFF.toByte() // A（不透明）
        }
    }

    val bitmap = Bitmap()
    bitmap.allocN32Pixels(sizePx, sizePx, false)
    bitmap.installPixels(
        ImageInfo.makeN32(sizePx, sizePx, ColorAlphaType.OPAQUE),
        bytes,
        sizePx * 4,
    )
    Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}.getOrNull()
