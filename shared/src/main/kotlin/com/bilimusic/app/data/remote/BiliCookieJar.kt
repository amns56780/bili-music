package com.bilimusic.app.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp CookieJar 与 [CookieStore] 的桥接：
 * - 扫码登录成功时，passport 通过 Set-Cookie 下发 SESSDATA 等，这里自动接住并加密落盘
 * - 之后每个 B 站请求自动带上 Cookie，不用每处手写（任务书 4.1）
 */
@Singleton
class BiliCookieJar @Inject constructor(
    private val store: CookieStore,
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty() || !isBiliHost(url.host)) return
        store.putAll(cookies.associate { it.name to it.value })
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isBiliHost(url.host)) return emptyList()
        return store.pairs().mapNotNull { (name, value) ->
            runCatching { Cookie.parse(url, "$name=$value") }.getOrNull()
        }
    }

    companion object {
        private val BILI_HOST_SUFFIXES = listOf(
            "bilibili.com",
            "hdslb.com",
            "bilivideo.com",
            "biliapi.net",
            "b23.tv",
        )

        fun isBiliHost(host: String): Boolean =
            BILI_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }
}
