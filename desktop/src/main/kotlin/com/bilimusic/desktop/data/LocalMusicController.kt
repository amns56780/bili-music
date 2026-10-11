package com.bilimusic.desktop.data

import com.bilimusic.app.domain.model.PlaylistSourceType
import com.bilimusic.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 本地音乐界面状态 */
data class LocalMusicUiState(
    val scanning: Boolean = false,
    val files: List<LocalAudioFile> = emptyList(),
    val message: String? = null,
    /** 当前生效的扫描目录 */
    val dirs: List<String> = emptyList(),
    /** 是否有用户自定义目录（否则就是默认的 音乐/下载/桌面） */
    val custom: Boolean = false,
) {
    val totalSizeMb: Long get() = files.sumOf { it.sizeBytes } / (1024 * 1024)
}

/**
 * 本地音乐（FR-5 桌面版）：扫描电脑里的音频 → 直接播放 / 导入到歌单。
 *
 * 扫描目录默认是「音乐 / 下载 / 桌面」，用户可以在界面里添加自己的文件夹
 * （比如 D:\Music），选择结果持久化到 ~/.bilimusic/local-dirs.json。
 *
 * 与安卓端语义一致：本地文件走本地播放链路，不进在线缓存；导入歌单后
 * 与 B 站曲目混排，队列/播放模式/定时这些能力都能用上。
 */
class LocalMusicController(
    private val scanner: LocalMusicScanner,
    private val store: PlaylistStore,
    private val scope: CoroutineScope,
) {

    companion object {
        const val TAG = "LocalMusicController"
        const val LOCAL_PLAYLIST_TITLE = "本地音乐"
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }

    private val _state = MutableStateFlow(LocalMusicUiState())
    val state: StateFlow<LocalMusicUiState> = _state.asStateFlow()

    private val dirsFile = File(File(System.getProperty("user.home"), ".bilimusic"), "local-dirs.json")

    /** 用户自定义目录；空表示还没自定义过 */
    private var customDirs: List<String> = loadDirs()

    private fun loadDirs(): List<String> = runCatching {
        if (!dirsFile.isFile) emptyList() else json.decodeFromString<List<String>>(dirsFile.readText())
    }.getOrElse {
        AppLog.w(TAG, "读取扫描目录配置失败，改用默认目录", it)
        emptyList()
    }

    private fun saveDirs(dirs: List<String>) {
        customDirs = dirs
        runCatching {
            dirsFile.parentFile?.mkdirs()
            dirsFile.writeText(json.encodeToString(dirs))
        }.onFailure { AppLog.w(TAG, "保存扫描目录失败", it) }
    }

    /** 当前生效的扫描目录（给界面显示） */
    fun scanDirs(): List<String> =
        if (customDirs.isEmpty()) scanner.defaultDirs().map { it.absolutePath } else customDirs

    fun isCustom(): Boolean = customDirs.isNotEmpty()

    private fun effectiveDirs(): List<File> =
        if (customDirs.isEmpty()) {
            scanner.defaultDirs()
        } else {
            customDirs.map { File(it) }.filter { it.isDirectory }
        }

    /**
     * 添加一个扫描目录。
     *
     * 第一次添加时会把当前默认目录一起保留，避免"加一个文件夹就只剩这一个"。
     * @return true 表示新增成功；false 表示不是目录或已存在
     */
    fun addDir(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory) return false
        val abs = dir.absolutePath
        val base = if (customDirs.isEmpty()) scanDirs() else customDirs
        if (base.any { it.equals(abs, ignoreCase = true) }) return false
        saveDirs(base + abs)
        scan()
        return true
    }

    /** 移除一个扫描目录；全部移除后自动回到默认目录 */
    fun removeDir(path: String) {
        val base = if (customDirs.isEmpty()) scanDirs() else customDirs
        saveDirs(base.filterNot { it.equals(path, ignoreCase = true) })
        scan()
    }

    /** 恢复默认扫描目录（音乐 / 下载 / 桌面） */
    fun resetDirs() {
        runCatching { if (dirsFile.isFile) dirsFile.delete() }
        customDirs = emptyList()
        scan()
    }

    fun scan() {
        scope.launch {
            _state.value = _state.value.copy(
                scanning = true,
                message = "正在扫描本地音频…",
                dirs = scanDirs(),
                custom = isCustom(),
            )
            val files = withContext(Dispatchers.IO) { scanner.scan(effectiveDirs()) }
            _state.value = LocalMusicUiState(
                scanning = false,
                files = files,
                dirs = scanDirs(),
                custom = isCustom(),
                message = if (files.isEmpty()) {
                    "没有在这些目录里找到音频文件（支持 mp3/flac/m4a/aac/wav/ogg）"
                } else {
                    "找到 ${files.size} 个音频文件"
                },
            )
        }
    }

    /** 导入到「本地音乐」歌单（复用同一个，不重复建）；返回该歌单 id */
    fun importToLocalPlaylist(files: List<LocalAudioFile>): Long? {
        if (files.isEmpty()) return null
        val title = LOCAL_PLAYLIST_TITLE
        val playlistId = store.findBySource(PlaylistSourceType.MANUAL, title)?.id
            ?: store.createPlaylist(title, PlaylistSourceType.MANUAL)
        val added = store.addSongs(
            playlistId,
            files.map { file ->
                NewSong(
                    // 本地曲目统一用 "local" 当 bvid，cid 用路径哈希保证同一文件判重
                    bvid = "local",
                    cid = file.path.hashCode().toLong(),
                    title = file.title,
                    artist = file.folder.ifBlank { "本地文件" },
                    localPath = file.path,
                )
            },
        )
        _state.value = _state.value.copy(message = "已导入「$title」：新增 $added 首（重复的自动跳过）")
        return playlistId
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
