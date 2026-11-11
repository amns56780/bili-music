package com.bilimusic.app.data.player

import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliApi
import com.bilimusic.app.data.remote.FailureKind
import com.bilimusic.app.data.remote.RequestThrottle
import com.bilimusic.app.data.remote.WbiSigner
import com.bilimusic.app.data.remote.apiCall
import com.bilimusic.app.domain.model.AudioQualityOption
import com.bilimusic.app.domain.model.AudioStream
import com.bilimusic.app.domain.model.DashAudioTrack
import com.bilimusic.app.domain.model.selectAudioTrack
import com.bilimusic.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-4 / FR-10 取音频流（安卓与桌面共用）。
 *
 * 关键点（任务书 4.3）：
 * - 取流 URL **只有 120 分钟有效期**，所以这里做「带过期时间的 URL 缓存」：
 *   过期前直接复用，过期或播放器报 403 时重新取流
 * - 优先 wbi/playurl，签名相关失败先强刷 mixin_key 再试一次，再不行退到不带 WBI 的备选接口
 * - 保留 baseUrl + backupUrl[]：主 URL 失败时下一次解析切到备用 URL
 * - 音质：按用户档位匹配，匹配不到按带宽从高到低降级，保证任何情况下都能播
 */
@Singleton
class StreamUrlRepository @Inject constructor(
    private val api: BiliApi,
    private val wbiSigner: WbiSigner,
    private val qualityProvider: AudioQualityProvider,
    private val throttle: RequestThrottle,
) {

    private val cache = ConcurrentHashMap<String, AudioStream>()
    private val urlToKey = ConcurrentHashMap<String, String>()
    private val attemptCounter = ConcurrentHashMap<String, Int>()

    /** 取流（带缓存）。key = bvid-cid */
    suspend fun resolve(
        bvid: String,
        cid: Long,
        forceRefresh: Boolean = false,
    ): AudioStream? {
        val key = cacheKey(bvid, cid)
        if (!forceRefresh) {
            cache[key]?.let { cached ->
                if (!cached.isExpiringSoon()) return cached
                AppLog.i(TAG, "缓存里的取流地址即将过期，重新取流：$key")
            }
        }

        val option = qualityProvider.current()
        val stream = fetch(bvid, cid, option) ?: return cache[key]
        cache[key] = stream
        urlToKey[stream.url] = key
        stream.backupUrls.forEach { urlToKey[it] = key }
        attemptCounter[key] = 0
        AppLog.i(
            TAG,
            "取流成功 $key 音质=${stream.displayLabel} 带宽=${stream.bandwidth} " +
                "剩余有效期=${stream.remainingMs() / 1000}s 备用地址=${stream.backupUrls.size}",
        )
        return stream
    }

    /** 播放器报 403 / URL 失效时调用：失效缓存，下次解析会重新取流 */
    fun invalidateByUrl(url: String) {
        val key = urlToKey.remove(url) ?: return
        cache.remove(key)
        AppLog.w(TAG, "地址失效（403），已清除缓存：$key")
    }

    fun invalidate(bvid: String, cid: Long) {
        val key = cacheKey(bvid, cid)
        cache.remove(key)?.let { urlToKey.remove(it.url) }
    }

    fun cached(bvid: String, cid: Long): AudioStream? = cache[cacheKey(bvid, cid)]

    /**
     * 主地址失败时轮换到备用地址：把当前 attempt 加一，下次解析返回 baseUrl 或 backupUrl。
     * 安卓端由 BiliAudioResolver 调用；桌面端由播放器重试逻辑调用。
     */
    fun rotateToBackup(bvid: String, cid: Long): String? {
        val key = cacheKey(bvid, cid)
        val stream = cache[key] ?: return null
        val all = listOf(stream.url) + stream.backupUrls
        if (all.size <= 1) return null
        val next = (attemptCounter[key] ?: 0) + 1
        attemptCounter[key] = next
        return all[next % all.size]
    }

    private suspend fun fetch(
        bvid: String,
        cid: Long,
        option: AudioQualityOption,
    ): AudioStream? {
        val baseParams = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to "4048", // 任务书：一次把所有格式都请求到
            "fnver" to "0",
            "fourk" to "1",
            "qn" to "0",
        )

        throttle.await()
        var result = apiCall(maxAttempts = 2) { api.playUrl(wbiSigner.sign(baseParams)) }

        // 签名可能已失效：强刷 mixin_key 再试一次
        if (result is ApiResult.Error &&
            (result.failure.kind == FailureKind.PERMISSION || result.failure.kind == FailureKind.UNKNOWN)
        ) {
            AppLog.w(TAG, "wbi/playurl 失败（${result.failure.userMessage}），强刷 WBI key 后重试")
            wbiSigner.invalidate()
            throttle.await()
            result = apiCall(maxAttempts = 2) { api.playUrl(wbiSigner.sign(baseParams)) }
        }

        // 仍然失败 → 退到备选接口（不需要 WBI）
        if (result is ApiResult.Error) {
            AppLog.w(TAG, "改用备选取流接口 /x/player/playurl")
            throttle.await()
            result = apiCall(maxAttempts = 2) {
                api.playUrlLegacy(
                    mapOf(
                        "bvid" to bvid,
                        "cid" to cid.toString(),
                        "fnval" to "4048",
                        "fnver" to "0",
                    ),
                )
            }
        }

        val data = (result as? ApiResult.Success)?.data ?: run {
            AppLog.e(TAG, "取流失败：${(result as? ApiResult.Error)?.failure?.userMessage}")
            return null
        }

        val tracks = data.dash?.audio.orEmpty().map { dto ->
            DashAudioTrack(
                id = dto.id,
                baseUrl = dto.baseUrl,
                backupUrls = dto.backupUrls,
                bandwidth = dto.bandwidth,
                mimeType = dto.mimeType,
                codecs = dto.codecs,
            )
        }.filter { it.baseUrl.isNotBlank() }

        val picked = selectAudioTrack(tracks, option)
        if (picked == null) {
            // 极端兜底：dash 为空时用 durl
            val durl = data.durl.orEmpty().firstOrNull { it.url.isNotBlank() }
            if (durl != null) {
                return AudioStream(
                    url = durl.url,
                    backupUrls = durl.backupUrl.orEmpty(),
                    qualityId = data.quality,
                    bandwidth = 0L,
                    mimeType = "",
                    codecs = "",
                    deadlineEpochSeconds = parseDeadline(durl.url) ?: defaultDeadline(),
                    requested = option,
                )
            }
            AppLog.e(TAG, "接口没有返回可用的音频流（dash.audio 为空）")
            return null
        }

        return AudioStream(
            url = picked.baseUrl,
            backupUrls = picked.backupUrls,
            qualityId = picked.id,
            bandwidth = picked.bandwidth,
            mimeType = picked.mimeType,
            codecs = picked.codecs,
            deadlineEpochSeconds = parseDeadline(picked.baseUrl) ?: defaultDeadline(),
            requested = option,
        )
    }

    /** 从 URL 里取 deadline（epoch 秒）；取不到就按「现在 + 110 分钟」算 */
    private fun parseDeadline(url: String): Long? =
        DEADLINE_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun defaultDeadline(): Long =
        System.currentTimeMillis() / 1000L + DEFAULT_TTL_SECONDS

    private fun cacheKey(bvid: String, cid: Long) = "$bvid-$cid"

    companion object {
        private const val TAG = "StreamUrlRepository"

        /** 任务书：120 分钟有效期，取不到 deadline 时保守按 110 分钟算 */
        private const val DEFAULT_TTL_SECONDS = 110L * 60L

        private val DEADLINE_REGEX = Regex("""[?&]deadline=(\d+)""")
    }
}
