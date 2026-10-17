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

    companion object {
        private const val TAG = "LocalMusicScanner"

        /** 真正会被 VLC 播放的音频扩展名（扫描时按这个过滤） */
        private val EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma",
            "ape", "alac", "aiff", "aif", "dsf", "dff", "mka",
        )

        /**
         * 夹在中间的那层「伪扩展名」：下载器 / 加密格式留下的后缀。
         * 例如 酷狗的 .kgm.flac、网易云的 .ncm.flac、QQ音乐的 .qmcflac.flac。
         * 标题生成时要把它们和真正的音频扩展名一起剥掉。
         */
        private val WRAPPER_EXTENSIONS = setOf(
            "kgm", "kgma", "ncm", "qmc0", "qmc2", "qmc3", "qmcflac", "qmcogg",
            "mflac", "mgg", "tm0", "tm2", "tm3", "xm",
        )

        /** 单个目录的递归深度上限，避免误扫到很深的工程目录 */
        private const val MAX_DEPTH = 6
        private const val MAX_FILES = 5000

        /**
         * 从文件名里剥出显示用的标题。
         *
         * 不能只用 nameWithoutExtension —— 那只去掉最后一层，遇到
         * 「Josh Vietti - Where Is the Love.kgm.flac」会留下 ".kgm"。
         * 这里反复剥：只要是音频扩展名或者已知的伪扩展名就继续剥。
         * 普通歌名里的点（如 "Song.2020"）不会被误伤。
         */
        fun titleOf(fileName: String): String {
            var name = fileName
            while (true) {
                val dot = name.lastIndexOf('.')
                if (dot <= 0) break
                val ext = name.substring(dot + 1).lowercase()
                if (ext in EXTENSIONS || ext in WRAPPER_EXTENSIONS) {
                    name = name.substring(0, dot)
                } else {
                    break
                }
            }
            return name.ifBlank { fileName }
        }
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
                    title = titleOf(child.name),
                    folder = child.parentFile?.name.orEmpty(),
                    sizeBytes = child.length(),
                )
            }
        }
    }
}
