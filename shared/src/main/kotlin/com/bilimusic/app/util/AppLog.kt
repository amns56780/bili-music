package com.bilimusic.app.util

/**
 * 跨平台日志门面。
 *
 * 共享层（纯 JVM）不能直接用 `android.util.Log`，所以这里做一个可替换的实现：
 * 安卓端在 Application 里把它接到 `android.util.Log`（继续进 logcat），
 * 桌面端保持默认（打到标准输出）。
 */
object AppLog {

    /** 日志落地实现；level 取 "I"/"W"/"E" */
    var logger: (level: String, tag: String, message: String, error: Throwable?) -> Unit =
        { level, tag, message, error ->
            val suffix = error?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
            println("[$level] $tag: $message$suffix")
        }

    fun i(tag: String, message: String) = logger("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = logger("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = logger("E", tag, message, error)
}
