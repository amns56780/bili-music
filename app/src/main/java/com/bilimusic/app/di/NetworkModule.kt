package com.bilimusic.app.di

import com.bilimusic.app.BuildConfig
import com.bilimusic.app.data.local.prefs.PlaybackPrefs
import com.bilimusic.app.data.local.prefs.SecurePrefs
import com.bilimusic.app.data.player.AudioQualityProvider
import com.bilimusic.app.data.remote.BiliApi
import com.bilimusic.app.data.remote.BiliCookieJar
import com.bilimusic.app.data.remote.BiliHeaderInterceptor
import com.bilimusic.app.data.remote.KeyValueStore
import com.bilimusic.app.domain.model.AudioQualityOption
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络层依赖：Json / OkHttp / Retrofit / BiliApi。
 *
 * 这一个 OkHttpClient 同时给 Retrofit 和 Phase 4 的 Media3 音频数据源使用，
 * 所以 Referer / UA 的注入只需要维护一处。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * 共享层（shared 模块）里的 CookieStore 需要一个键值存储，
     * 安卓端用加密偏好实现；桌面端会换成用户目录下的本地文件。
     */
    @Provides
    @Singleton
    fun provideKeyValueStore(securePrefs: SecurePrefs): KeyValueStore = securePrefs

    /**
     * 共享层的取流逻辑需要知道当前音质档位，安卓端从 DataStore 读。
     */
    @Provides
    @Singleton
    fun provideAudioQualityProvider(prefs: PlaybackPrefs): AudioQualityProvider =
        AudioQualityProvider { AudioQualityOption.fromNameOrAuto(prefs.audioQualityOption.first()) }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cookieJar: BiliCookieJar,
        headerInterceptor: BiliHeaderInterceptor,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(headerInterceptor)
            // 任务书 4.6：连接 10s / 读 20s
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            // BASIC 只打印请求行与响应码，不打印 Cookie / 请求头，避免登录态泄漏到日志
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.bilibili.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideBiliApi(retrofit: Retrofit): BiliApi = retrofit.create(BiliApi::class.java)
}
