package com.bilimusic.desktop.data

import com.bilimusic.app.util.AppLog
import java.io.File

/** 扫描到的一个本地音频文件 */
data class LocalAudioFile(
    val path: String,
    val title: String,
    val folder: String,
    val sizeBytes: Long,
) {
    val fileName: String get() = File(path).name
}

/**
 * 本地音乐扫描（FR-5 的桌面版）。
 *
 * 只扫描几个常见目录（音乐/下载/桌面 + 用户自定义目录），不遍历整块磁盘，
 * 避免卡住界面与误扫系统文件。
 *
 * 标题取文件名（去掉扩展名）—— 桌面端暂不解析 ID3 标签，这样零额外依赖；
 * 后续要显示真实艺术家/专辑可以再引入元数据库。
 */
class LocalMusicScanner {

    private companion object {
        const val TAG = "LocalMusicScanner"
        val EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma")
        /** 单个目录的递归深度上限，避免误扫到很深的工程目录 */
        const val MAX_DEPTH = 6
        const val MAX_FILES = 5000
    }

    /** 默认扫描目录 */
    fun defaultDirs(): List<File> {
        val home = File(System.getProperty("user.home"))
        return listOf(
            File(home, "Music"),
            File(home, "Downloads"),
            File(home, "Desktop"),
        ).filter { it.isDirectory }
    }

    /** 扫描（阻塞，调用方放到 IO 线程/协程里） */
    fun scan(dirs: List<File> = defaultDirs()): List<LocalAudioFile> {
        val out = mutableListOf<LocalAudioFile>()
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            runCatching { walk(dir, 0, out) }
                .onFailure { AppLog.w(TAG, "扫描目录失败：${dir.absolutePath}", it) }
            if (out.size >= MAX_FILES) return out
        }
        return out.sortedBy { it.title.lowercase() }
    }

    private fun walk(dir: File, depth: Int, out: MutableList<LocalAudioFile>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            if (out.size >= MAX_FILES) return
            if (child.isDirectory) {
                if (!child.name.startsWith(".")) walk(child, depth + 1, out)
            } else if (child.extension.lowercase() in EXTENSIONS) {
                out += LocalAudioFile(
                    path = child.absolutePath,
                    title = child.nameWithoutExtension,
                    folder = child.parentFile?.name.orEmpty(),
                    sizeBytes = child.length(),
                )
            }
        }
    }
}
