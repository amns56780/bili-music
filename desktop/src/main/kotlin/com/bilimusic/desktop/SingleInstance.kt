package com.bilimusic.desktop

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * 单实例保护。
 *
 * 两个实例同时跑会出两套托盘图标、两个播放器（而且托盘图标还会互相打架）。
 * 这里用最低成本的本机 socket 方案：
 * - 第一个实例监听 127.0.0.1 的固定端口；
 * - 后来的实例连上去发一个 show，然后自己退出；
 * - 第一个实例收到后把窗口显示出来。
 *
 * 局域网不可达（只绑 loopback），端口被别的程序占用时退化成允许多开（宁可多开也不要起不来）。
 */
object SingleInstance {

    private const val PORT = 47823
    private const val CMD_SHOW = "show"

    /** 第一个实例收到「再来一个」时调用，用来把窗口显示出来 */
    val onActivate: AtomicReference<() -> Unit> = AtomicReference { }

    @Volatile
    private var server: ServerSocket? = null

    /** @return true = 当前进程是唯一实例；false = 已有实例在跑（已通知它显示窗口，本进程应直接退出） */
    fun acquire(): Boolean {
        if (notifyExisting()) return false
        return runCatching {
            val ss = ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())
            server = ss
            thread(isDaemon = true, name = "bm-single-instance") {
                while (!ss.isClosed) {
                    runCatching {
                        ss.accept().use { socket ->
                            val cmd = socket.getInputStream().bufferedReader().readLine()
                            if (cmd == CMD_SHOW) {
                                SwingUtilities.invokeLater { onActivate.get().invoke() }
                            }
                        }
                    }
                }
            }
            true
        }.getOrDefault(true)
    }

    private fun notifyExisting(): Boolean = runCatching {
        Socket(InetAddress.getLoopbackAddress(), PORT).use { socket ->
            socket.getOutputStream().write((CMD_SHOW + "\n").toByteArray())
            socket.getOutputStream().flush()
        }
        true
    }.getOrDefault(false)
}
