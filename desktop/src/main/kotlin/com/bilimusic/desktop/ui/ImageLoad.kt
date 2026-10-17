package com.bilimusic.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import com.bilimusic.desktop.data.StoredPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jetbrains.skia.Image
import java.io.File

/** 全局提供带 Referer/UA 的 OkHttpClient，封面图才不会被 B 站 403 */
val LocalImageClient = staticCompositionLocalOf<OkHttpClient?> { null }

/** 异步加载本地图片文件作为封面 */
@Composable
fun rememberLocalImage(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = File(path).readBytes()
                    Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}

/**
 * 歌单封面：优先本地图片 → 网络自定义封面 → 渐变占位（同一歌单颜色稳定）。
 * 侧栏与歌单页共用，保证两处表现一致。
 */
@Composable
fun PlaylistCover(
    playlist: StoredPlaylist,
    size: Dp,
    radius: Dp,
) {
    val client = LocalImageClient.current
    val localBitmap = rememberLocalImage(playlist.coverPath)
    val remoteBitmap = rememberRemoteImage(
        url = if (playlist.coverPath.isNullOrBlank()) playlist.coverUrl else null,
        client = client ?: fallbackClient,
    )
    BmCover(
        cover = localBitmap ?: remoteBitmap,
        seed = playlist.title,
        size = size,
        radius = radius,
    )
}

/** 没有注入 client 时的兜底（正常路径不会用到），避免空指针 */
internal val fallbackClient: OkHttpClient by lazy { OkHttpClient() }
