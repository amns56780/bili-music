package com.bilimusic.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.bilimusic.app.util.AppLog
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Coil 复用同一个 OkHttpClient：封面图（i0.hdslb.com 等）同样需要
 * Referer / User-Agent，否则部分图片会 403。
 */
@HiltAndroidApp
class BiliMusicApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // 共享层（shared）用的是自己的日志门面，这里接到 android.util.Log，保证继续进 logcat
        AppLog.logger = { level, tag, message, error ->
            when (level) {
                "E" -> Log.e(tag, message, error)
                "W" -> Log.w(tag, message, error)
                else -> Log.i(tag, message)
            }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { okHttpClient }
        .crossfade(true)
        .build()
}
