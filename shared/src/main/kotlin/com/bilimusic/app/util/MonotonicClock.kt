package com.bilimusic.app.util

/**
 * 单调时钟（毫秒）。
 *
 * 安卓原来用 `SystemClock.elapsedRealtime()`（开机以来毫秒数），桌面端没有这个类。
 * `System.nanoTime()` 在两个平台上都单调递增，用来做「缓存多久过期」「请求间隔」这类判断足够，
 * 而且**不受用户改系统时间影响**。
 *
 * 注意：它不包含关机时间（设备休眠时的行为与 elapsedRealtime 略有差异），
 * 但这里只用于 TTL 与节流，精度要求不高。
 */
object MonotonicClock {
    fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}
