package com.bilimusic.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image

/**
 * 异步加载网络封面图（hdslb 图片）。
 *
 * 桌面端没有 Coil，直接用共享层那个 OkHttpClient（带 Referer/UA，图片才不会 403）
 * 取字节，再交给 Skia 解码成 Compose 位图。
 */
@Composable
fun rememberRemoteImage(url: String?, client: OkHttpClient): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, client) {
        bitmap = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        val bytes = response.body?.bytes() ?: return@use null
                        Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }
    return bitmap
}
