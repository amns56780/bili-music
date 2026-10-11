package com.bilimusic.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * b23.tv 短链解析：跟随 302 拿到最终 URL，再从里面取 BV / av 号。
 */
@Singleton
class ShortLinkResolver @Inject constructor(
    private val client: OkHttpClient,
) {

    /** 返回最终 URL；失败返回 null */
    suspend fun resolve(shortUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(shortUrl).build()
            client.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        }.getOrNull()
    }
}
