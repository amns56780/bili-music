package com.bilimusic.desktop.data

import com.bilimusic.app.domain.model.PlaylistSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredPlaylist(
    val id: Long,
    val title: String,
    /** 对应 [PlaylistSourceType] 的名字（FAV / TO_VIEW / SPACE / SEASON / LOCAL / MANUAL） */
    val sourceType: String = PlaylistSourceType.MANUAL.name,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class StoredSong(
    val id: Long,
    val playlistId: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val artist: String = "",
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val sortOrder: Int = 0,
    /** 非空表示本地文件（bvid 为 "local"），播放时直接交给 VLC */
    val localPath: String? = null,
)

@Serializable
private data class PlaylistDb(
    val playlists: List<StoredPlaylist> = emptyList(),
    val songs: List<StoredSong> = emptyList(),
    val nextPlaylistId: Long = 1L,
    val nextSongId: Long = 1L,
)

/**
 * 桌面端歌单存储（FR-2 / FR-3）。
 *
 * 落盘用 JSON 文件 `~/.bilimusic/playlists.json`：曲目量级（几千首）下足够快，
 * 且不需要再引入数据库依赖。写入是「整文件覆盖 + 临时文件改名」，避免写一半崩掉把库写坏。
 *
 * 所有变更都通过 [StateFlow] 暴露给 UI，和安卓端 Room + Flow 的用法一致。
 */
class PlaylistStore(
    private val file: File = defaultFile(),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true },
) {

    private val lock = Any()
    private var db: PlaylistDb = loadFromDisk()

    private val _playlists = MutableStateFlow(db.playlists)
    val playlists: StateFlow<List<StoredPlaylist>> = _playlists.asStateFlow()

    private val _songs = MutableStateFlow(db.songs)
    val songs: StateFlow<List<StoredSong>> = _songs.asStateFlow()

    fun songsOf(playlistId: Long): List<StoredSong> =
        db.songs.filter { it.playlistId == playlistId }.sortedBy { it.sortOrder }

    // ---------------- 歌单 ----------------

    fun createPlaylist(title: String, sourceType: PlaylistSourceType = PlaylistSourceType.MANUAL): Long {
        synchronized(lock) {
            val playlist = StoredPlaylist(
                id = db.nextPlaylistId,
                title = title,
                sourceType = sourceType.name,
            )
            db = db.copy(
                playlists = db.playlists + playlist,
                nextPlaylistId = db.nextPlaylistId + 1,
            )
            persistLocked()
            return playlist.id
        }
    }

    /** 按来源找已存在的歌单（重复导入时复用，而不是建一堆同名歌单） */
    fun findBySource(sourceType: PlaylistSourceType, title: String): StoredPlaylist? =
        db.playlists.firstOrNull { it.sourceType == sourceType.name && it.title == title }

    fun renamePlaylist(id: Long, title: String) = mutate {
        it.copy(playlists = it.playlists.map { p -> if (p.id == id) p.copy(title = title) else p })
    }

    fun deletePlaylist(id: Long) = mutate {
        it.copy(
            playlists = it.playlists.filterNot { p -> p.id == id },
            songs = it.songs.filterNot { s -> s.playlistId == id },
        )
    }

    // ---------------- 曲目 ----------------

    /** 导入曲目：按 (bvid, cid) 去重，返回真正新增的数量 */
    fun addSongs(playlistId: Long, incoming: List<NewSong>): Int = synchronized(lock) {
        val existing = db.songs.filter { it.playlistId == playlistId }.map { it.bvid to it.cid }.toSet()
        var nextId = db.nextSongId
        var order = (db.songs.filter { it.playlistId == playlistId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = mutableListOf<StoredSong>()
        incoming.forEach { song ->
            val key = song.bvid to song.cid
            if (key in existing) return@forEach
            added += StoredSong(
                id = nextId++,
                playlistId = playlistId,
                bvid = song.bvid,
                cid = song.cid,
                title = song.title,
                artist = song.artist,
                coverUrl = song.coverUrl,
                durationMs = song.durationMs,
                sortOrder = order++,
                localPath = song.localPath,
            )
        }
        if (added.isEmpty()) return 0
        db = db.copy(songs = db.songs + added, nextSongId = nextId)
        persistLocked()
        added.size
    }

    fun removeSongs(songIds: Set<Long>) = mutate { data ->
        data.copy(songs = data.songs.filterNot { it.id in songIds })
    }

    /** 复制到另一个歌单（去重，返回新增数量） */
    fun copySongsTo(targetPlaylistId: Long, songIds: Set<Long>): Int {
        val picked = db.songs.filter { it.id in songIds }
        return addSongs(
            targetPlaylistId,
            picked.map { NewSong(it.bvid, it.cid, it.title, it.artist, it.coverUrl, it.durationMs) },
        )
    }

    /** 移动到另一个歌单 = 先复制、再删源（与安卓端语义一致） */
    fun moveSongsTo(targetPlaylistId: Long, songIds: Set<Long>): Int {
        val added = copySongsTo(targetPlaylistId, songIds)
        removeSongs(songIds)
        return added
    }

    /** 上移/下移一首（FR-3 排序） */
    fun moveSongOrder(songId: Long, delta: Int) = synchronized(lock) {
        val song = db.songs.firstOrNull { it.id == songId } ?: return
        val siblings = db.songs.filter { it.playlistId == song.playlistId }.sortedBy { it.sortOrder }
        val index = siblings.indexOfFirst { it.id == songId }
        val target = (index + delta).coerceIn(0, siblings.lastIndex)
        if (index == target) return
        val reordered = siblings.toMutableList().apply { add(target, removeAt(index)) }
        val orderById = reordered.withIndex().associate { (i, s) -> s.id to i }
        db = db.copy(
            songs = db.songs.map { s ->
                orderById[s.id]?.let { s.copy(sortOrder = it) } ?: s
            },
        )
        persistLocked()
    }

    private fun mutate(block: (PlaylistDb) -> PlaylistDb) = synchronized(lock) {
        db = block(db)
        persistLocked()
    }

    private fun persistLocked() {
        _playlists.value = db.playlists
        _songs.value = db.songs
        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时文件再改名：避免写到一半崩溃把整个歌单库写坏
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(db))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun loadFromDisk(): PlaylistDb = runCatching {
        if (!file.exists()) return@runCatching PlaylistDb()
        json.decodeFromString<PlaylistDb>(file.readText())
    }.getOrElse { PlaylistDb() }

    companion object {
        fun defaultDir(): File = File(System.getProperty("user.home"), ".bilimusic")

        fun defaultFile(): File = File(defaultDir(), "playlists.json")
    }
}

/** 待导入的曲目（catalog 层的中转结构） */
data class NewSong(
    val bvid: String,
    val cid: Long,
    val title: String,
    val artist: String = "",
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val localPath: String? = null,
)
