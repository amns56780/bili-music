package com.bilimusic.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一注入 B 站要求的请求头（任务书 4.1）。
 *
 * ★ 这套头同样会作用在音频流上（Phase 4 的 OkHttpDataSource 复用同一个 OkHttpClient），
 *   否则 bilivideo 域名下的 m4s 分片会返回 403 —— 这是取流失败最常见的原因。
 *
 * Cookie 不在这里手写，而是交给 [BiliCookieJar] 自动携带，避免两处维护。
 */
@Singleton
class BiliHeaderInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!BiliCookieJar.isBiliHost(request.url.host)) {
            return chain.proceed(request)
        }
        val builder = request.newBuilder()
        if (request.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", USER_AGENT)
        }
        if (request.header("Referer").isNullOrBlank()) {
            builder.header("Referer", REFERER)
        }
        if (request.header("Origin").isNullOrBlank()) {
            builder.header("Origin", ORIGIN)
        }
        return chain.proceed(builder.build())
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
        const val REFERER = "https://www.bilibili.com/"
        const val ORIGIN = "https://www.bilibili.com"
    }
}
