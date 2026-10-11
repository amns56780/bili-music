package com.bilimusic.app.data.remote

import com.bilimusic.app.data.remote.dto.NavData
import com.bilimusic.app.util.AppLog
import com.bilimusic.app.util.MonotonicClock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WBI 签名（任务书 4.2）。
 *
 * 步骤：nav 接口取 img_url / sub_url → 取文件名 → raw = imgKey + subKey
 * → 用固定重排表逐位取值拼成新串、取前 32 位得 mixin_key
 * → 业务参数加 wts、按 key 升序、值过滤 `!'()*`、拼 query → w_rid = md5(query + mixin_key)。
 *
 * mixin_key 缓存一天，不每个请求都去请求 nav。
 * 时间源换成 [MonotonicClock]（纯 JVM），日志换成 [AppLog]，行为不变。
 */
@Singleton
class WbiSigner @Inject constructor(
    private val api: BiliApi,
) {

    private var mixinKey: String? = null
    private var fetchedAtElapsed: Long = 0L

    /** 拿到（必要时刷新）mixin_key。失败时返回上次缓存；从未成功过则返回 null */
    suspend fun ensureMixinKey(forceRefresh: Boolean = false): String? {
        val now = MonotonicClock.elapsedMs()
        val cached = mixinKey
        if (!forceRefresh && cached != null && now - fetchedAtElapsed < CACHE_TTL_MS) {
            return cached
        }
        return when (val result = apiCall { api.nav() }) {
            is ApiResult.Success -> {
                val key = deriveMixinKeyFromNav(result.data)
                if (key.isNullOrBlank()) {
                    AppLog.w(TAG, "nav 未返回可用的 wbi_img，沿用旧 mixin_key")
                    cached
                } else {
                    mixinKey = key
                    fetchedAtElapsed = now
                    key
                }
            }

            is ApiResult.Error -> {
                AppLog.w(TAG, "获取 WBI key 失败：${result.failure.userMessage}")
                cached
            }
        }
    }

    /**
     * 给业务参数签名，返回「原参数 + wts + w_rid」。
     * 调用方把这些参数全部作为 query 发出去即可。
     */
    suspend fun sign(params: Map<String, String>, forceRefreshKey: Boolean = false): Map<String, String> =
        sign(params, ensureMixinKey(forceRefreshKey))

    internal fun sign(params: Map<String, String>, mixinKey: String?): Map<String, String> =
        signWith(params, mixinKey, System.currentTimeMillis() / 1000L)

    /** 让外部可以在签名报错时强制刷新一次 key 再试 */
    fun invalidate() {
        mixinKey = null
        fetchedAtElapsed = 0L
    }

    private fun deriveMixinKeyFromNav(nav: NavData): String? {
        val img = nav.wbiImg?.imgUrl.orEmpty()
        val sub = nav.wbiImg?.subUrl.orEmpty()
        if (img.isBlank() || sub.isBlank()) return null
        return deriveMixinKey(img, sub)
    }

    companion object {
        private const val TAG = "WbiSigner"
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        /** 官方 WBI 重排表（任务书 4.2 原样照抄，不要改） */
        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )

        /** 从 img_url / sub_url 推导 mixin_key */
        fun deriveMixinKey(imgUrl: String, subUrl: String): String {
            val imgKey = fileKeyOf(imgUrl)
            val subKey = fileKeyOf(subUrl)
            val raw = imgKey + subKey
            if (raw.length < 64) return ""
            val permuted = MIXIN_KEY_ENC_TAB.map { raw[it] }.joinToString(separator = "")
            return permuted.take(32)
        }

        /** https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png → 7cd084941338484aae1ad9425b84077c */
        private fun fileKeyOf(url: String): String =
            url.substringAfterLast('/').substringBeforeLast('.')

        /** 参与签名的参数值要过滤掉 !'()* 这几个字符 */
        fun sanitize(value: String): String = value.filterNot { it in "!'()*" }

        /**
         * 纯函数版签名（便于单元测试）：参数加 wts、按 key 升序、值过滤特殊字符，
         * 再拼上 mixin_key 做 md5 得到 w_rid。
         */
        fun signWith(
            params: Map<String, String>,
            mixinKey: String?,
            wtsSeconds: Long,
        ): Map<String, String> {
            val all = LinkedHashMap(params).apply { put("wts", wtsSeconds.toString()) }
            if (mixinKey.isNullOrBlank()) {
                // 拿不到 key 也把 wts 带上，至少让请求符合时间戳要求
                return all
            }
            val query = all.entries
                .sortedBy { it.key }
                .joinToString("&") { (k, v) -> "$k=${sanitize(v)}" }
            all["w_rid"] = md5(query + mixinKey)
            return all
        }

        fun md5(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
