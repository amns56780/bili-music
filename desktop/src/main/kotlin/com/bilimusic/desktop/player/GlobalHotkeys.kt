package com.bilimusic.desktop.player

import com.bilimusic.app.util.AppLog
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser

/**
 * 系统级全局媒体键（Windows）。
 *
 * 为什么不用 JNativeHook：它要在运行时解包原生库，而受限环境（沙箱 / 某些企业策略）
 * 下解包会失败。这里直接用 **JNA 调 Win32 `RegisterHotKey`** —— JNA 是 vlcj 本来就带的依赖，
 * 不需要额外原生库，也就能稳定工作。
 *
 * 实现要点：`RegisterHotKey` 传 null 窗口句柄时，WM_HOTKEY 会投递到**调用线程的消息队列**，
 * 所以在专用线程里注册 + 跑 `GetMessage` 循环即可；线程设为守护线程，不影响退出。
 * 热键被别的程序占用时注册会失败，此时只记录日志、不影响其它功能。
 */
class GlobalHotkeys(
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) {

    private companion object {
        const val TAG = "GlobalHotkeys"

        const val ID_PLAY_PAUSE = 1
        const val ID_NEXT = 2
        const val ID_PREVIOUS = 3

        /** Win32 虚拟键码 */
        const val VK_MEDIA_NEXT_TRACK = 0xB0
        const val VK_MEDIA_PREV_TRACK = 0xB1
        const val VK_MEDIA_PLAY_PAUSE = 0xB3
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** 注册成功的热键数量（0 表示都没注册上，例如被别的播放器占用） */
    @Volatile
    var registeredCount: Int = 0
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "bilimusic-global-hotkeys").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        // 往消息队列投一条退出消息，让 GetMessage 返回，线程自然结束
        runCatching { User32.INSTANCE.PostThreadMessage(0, WinUser.WM_QUIT, null, null) }
        thread = null
    }

    private fun runLoop() {
        val user32 = User32.INSTANCE
        val bindings = listOf(
            Triple(ID_PLAY_PAUSE, VK_MEDIA_PLAY_PAUSE, "播放/暂停"),
            Triple(ID_NEXT, VK_MEDIA_NEXT_TRACK, "下一首"),
            Triple(ID_PREVIOUS, VK_MEDIA_PREV_TRACK, "上一首"),
        )
        var ok = 0
        bindings.forEach { (id, vk, label) ->
            // 线程 id：RegisterHotKey 传 null 句柄时，消息会投到这个线程的队列
            val registered = runCatching {
                user32.RegisterHotKey(null, id, 0, vk)
            }.getOrDefault(false)
            if (registered) {
                ok += 1
            } else {
                AppLog.w(TAG, "全局热键注册失败（可能被其它程序占用）：$label")
            }
        }
        registeredCount = ok
        AppLog.i(TAG, "全局媒体键注册完成：$ok/${bindings.size}")

        if (ok > 0) {
            val msg = WinUser.MSG()
            // Win32: GetMessage 返回 0 表示收到 WM_QUIT（JNA 映射为 int）
            while (running && user32.GetMessage(msg, null, 0, 0) != 0) {
                if (msg.message == WinUser.WM_HOTKEY) {
                    when (msg.wParam.toInt()) {
                        ID_PLAY_PAUSE -> runCatching { onPlayPause() }
                        ID_NEXT -> runCatching { onNext() }
                        ID_PREVIOUS -> runCatching { onPrevious() }
                    }
                }
            }
        }

        bindings.forEach { (id, _, _) -> runCatching { user32.UnregisterHotKey(null, id) } }
        AppLog.i(TAG, "全局媒体键已注销")
    }
}
