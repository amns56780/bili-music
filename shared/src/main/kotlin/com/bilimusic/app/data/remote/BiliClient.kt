package com.bilimusic.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * 共享层的「组合根」：把网络层需要的对象按固定顺序组装起来。
 *
 * 安卓端用 Hilt 注入（见 app 的 NetworkModule），桌面端没有 DI 框架，直接调用 [create]。
 * 这样两端的 OkHttp 配置（CookieJar / 伪造头 / 超时）只维护一份，不会出现「安卓能播、桌面 403」这类偏差。
 */
class BiliClient(
    val storage: KeyValueStore,
    val json: Json,
    val cookieStore: CookieStore,
    val okHttpClient: OkHttpClient,
    val api: BiliApi,
    val wbiSigner: WbiSigner,
    val requestThrottle: RequestThrottle,
    val shortLinkResolver: ShortLinkResolver,
) {

    companion object {
        private const val BASE_URL = "https://api.bilibili.com/"

        /** 与安卓端 NetworkModule 保持一致的 Json 策略 */
        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }

        fun create(storage: KeyValueStore): BiliClient {
            val json = defaultJson()
            val cookieStore = CookieStore(storage, json)
            val cookieJar = BiliCookieJar(cookieStore)
            val headerInterceptor = BiliHeaderInterceptor()

            // 任务书 4.6：连接 10s / 读 20s
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor(headerInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            val api = retrofit.create(BiliApi::class.java)

            return BiliClient(
                storage = storage,
                json = json,
                cookieStore = cookieStore,
                okHttpClient = client,
                api = api,
                wbiSigner = WbiSigner(api),
                requestThrottle = RequestThrottle(),
                shortLinkResolver = ShortLinkResolver(client),
            )
        }
    }
}
