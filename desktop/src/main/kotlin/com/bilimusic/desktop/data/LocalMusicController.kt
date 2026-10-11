package com.bilimusic.desktop.data

import com.bilimusic.app.domain.model.PlaylistSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本地音乐界面状态 */
data class LocalMusicUiState(
    val scanning: Boolean = false,
    val files: List<LocalAudioFile> = emptyList(),
    val message: String? = null,
) {
    val totalSizeMb: Long get() = files.sumOf { it.sizeBytes } / (1024 * 1024)
}

/**
 * 本地音乐（FR-5 桌面版）：扫描电脑里的音频 → 直接播放 / 导入到歌单。
 *
 * 与安卓端语义一致：本地文件走本地播放链路，不进在线缓存；导入歌单后
 * 与 B 站曲目混排，队列/播放模式/定时这些能力都能用上。
 */
class LocalMusicController(
    private val scanner: LocalMusicScanner,
    private val store: PlaylistStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(LocalMusicUiState())
    val state: StateFlow<LocalMusicUiState> = _state.asStateFlow()

    /** 默认扫描目录（UI 上显示给用户看） */
    fun scanDirs(): List<String> = scanner.defaultDirs().map { it.absolutePath }

    fun scan() {
        scope.launch {
            _state.value = _state.value.copy(scanning = true, message = "正在扫描本地音频…")
            val files = withContext(Dispatchers.IO) { scanner.scan() }
            _state.value = LocalMusicUiState(
                scanning = false,
                files = files,
                message = if (files.isEmpty()) {
                    "没有在「音乐 / 下载 / 桌面」里找到音频文件（支持 mp3/flac/m4a/aac/wav/ogg）"
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

    companion object {
        const val LOCAL_PLAYLIST_TITLE = "本地音乐"
    }
}
