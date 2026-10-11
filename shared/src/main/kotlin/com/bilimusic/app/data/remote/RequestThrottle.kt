package com.bilimusic.app.data.remote

import com.bilimusic.app.util.MonotonicClock
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批量导入的请求节流（任务书 4.6：相邻请求间隔 300~500ms），避免触发风控。
 */
@Singleton
class RequestThrottle @Inject constructor() {

    private var lastRequestAt = 0L

    suspend fun await(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        val now = MonotonicClock.elapsedMs()
        val wait = intervalMs - (now - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = MonotonicClock.elapsedMs()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 400L
    }
}
