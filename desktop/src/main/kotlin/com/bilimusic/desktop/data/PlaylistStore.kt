package com.bilimusic.desktop.data

import com.bilimusic.app.domain.model.ExistingPlaylistTitle
import com.bilimusic.app.domain.model.PlaylistNameConflict
import com.bilimusic.app.domain.model.PlaylistNaming
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
    /** 自定义封面（网络图，通常取自歌单内某首视频的封面） */
    val coverUrl: String? = null,
    /** 自定义封面（本地图片）；与 [coverUrl] 二选一，本地优先 */
    val coverPath: String? = null,
    /**
     * 导入来源的原始标题。
     *
     * 歌单因为重名被自动改名（「收藏夹」→「收藏夹 (2)」）以后，再次导入同一个来源
     * 还要靠原始标题认出它并复用，否则每导入一次就会多建一个歌单。
     * 手动创建的歌单为 null，比较时退回用 [title]。
     * 新增字段，旧版 playlists.json 缺省为 null（Json.ignoreUnknownKeys + 默认值兼容）。
     */
    val sourceTitle: String? = null,
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
    /** 歌单标题迁移版本：0 = 还没跑过历史重名自动改名迁移 */
    val titleMigrationVersion: Int = 0,
)

/**
 * 桌面端歌单存储（FR-2 / FR-3）。
 *
 * 落盘用 JSON 文件 `~/.bilimusic/playlists.json`：曲目量级（几千首）下足够快，
 * 且不需要再引入数据库依赖。写入是「整文件覆盖 + 临时文件改名」，避免写一半崩掉把库写坏。
 *
 * 所有变更都通过 [StateFlow] 暴露给 UI，和安卓端 Room + Flow 的用法一致。
 *
 * 重名规则统一走共享层的 [PlaylistNaming]：本类只提供「查重 / 生成不重名标题 / 按名删除」
 * 这些桌面端需要的查询辅助，具体交互（覆盖 / 自动重命名 / 取消）由 UI 层决定。
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

    init {
        // 注意：必须放在 _songs 初始化之后 —— Kotlin 的 init 块按声明顺序执行
        migrateLocalTitlesOnce()
        migrateDuplicateTitlesOnce()
    }

    /**
     * 一次性迁移：历史版本扫描本地音乐时用 nameWithoutExtension 取标题，
     * 遇到「xxx.kgm.flac」这种多层后缀会留下 ".kgm"。这里在启动时把已入库的
     * 本地歌曲标题重新算一遍（只改本地曲目，在线曲目不动）。
     */
    private fun migrateLocalTitlesOnce() = synchronized(lock) {
        var changed = false
        val fixed = db.songs.map { song ->
            val path = song.localPath
            if (path.isNullOrBlank()) {
                song
            } else {
                val newTitle = LocalMusicScanner.titleOf(java.io.File(path).name)
                if (newTitle.isNotBlank() && newTitle != song.title) {
                    changed = true
                    song.copy(title = newTitle)
                } else {
                    song
                }
            }
        }
        if (changed) {
            db = db.copy(songs = fixed)
            _songs.value = db.songs
            persistLocked()
            com.bilimusic.app.util.AppLog.i("PlaylistStore", "已修正本地音乐标题（去掉伪扩展名）")
        }
    }

    /**
     * 一次性迁移：桌面端以前完全不拦重名，历史数据里可能已经躺着同名的歌单。
     * 启动时按「先出现的保留原名，后出现的自动加 (2)/(3)…」改名（规则同 [PlaylistNaming.uniqueTitle]），
     * 顺便把标题统一成规范形式（去首尾空格、结尾 (n) 统一成半角）。
     *
     * 用 [PlaylistDb.titleMigrationVersion] 记账，只跑一次；之后改名都走 UI 的重名对话框。
     * 迁移是幂等的：即使标记没写进去（例如磁盘偶发失败）重跑也不会再改名。
     */
    private fun migrateDuplicateTitlesOnce() {
        synchronized(lock) {
            if (db.titleMigrationVersion >= TITLE_MIGRATION_VERSION) return@synchronized
            val taken = mutableSetOf<String>()
            var renamed = 0
            val fixed = db.playlists.map { playlist ->
                val normalized = PlaylistNaming.normalize(playlist.title) ?: PlaylistNaming.FALLBACK_TITLE
                val newTitle = PlaylistNaming.uniqueTitle(normalized, taken)
                taken += newTitle
                if (newTitle != playlist.title) {
                    renamed++
                    playlist.copy(title = newTitle)
                } else {
                    playlist
                }
            }
            db = db.copy(playlists = fixed, titleMigrationVersion = TITLE_MIGRATION_VERSION)
            persistLocked()
            if (renamed > 0) {
                com.bilimusic.app.util.AppLog.i(
                    "PlaylistStore",
                    "历史重名歌单已自动改名 " + renamed + " 个（在名字后加 \" (n)\"）",
                )
            }
        }
    }

    fun songsOf(playlistId: Long): List<StoredSong> =
        db.songs.filter { it.playlistId == playlistId }.sortedBy { it.sortOrder }

    // ---------------- 歌单 ----------------

    /**
     * 新建歌单。标题先走 [PlaylistNaming.normalize]（去空白、统一 (n) 写法）。
     *
     * 重名由调用方先处理：UI 用 [findConflict] 弹窗，决定覆盖时用 [createPlaylistOverwriting]，
     * 决定自动重命名时把 [uniqueTitle] 的结果传进来。本方法不做判重（导入时可能连续建歌单）。
     */
    fun createPlaylist(
        title: String,
        sourceType: PlaylistSourceType = PlaylistSourceType.MANUAL,
        sourceTitle: String? = null,
    ): Long {
        val normalized = PlaylistNaming.normalize(title) ?: PlaylistNaming.FALLBACK_TITLE
        synchronized(lock) {
            val playlist = StoredPlaylist(
                id = db.nextPlaylistId,
                title = normalized,
                sourceType = sourceType.name,
                sourceTitle = sourceTitle?.let { PlaylistNaming.normalize(it) },
            )
            db = db.copy(
                playlists = db.playlists + playlist,
                nextPlaylistId = db.nextPlaylistId + 1,
            )
            persistLocked()
            return playlist.id
        }
    }

    /** 覆盖式新建：先删掉同名旧歌单（连同其曲目），再用该名字新建 */
    fun createPlaylistOverwriting(
        title: String,
        sourceType: PlaylistSourceType = PlaylistSourceType.MANUAL,
        sourceTitle: String? = null,
    ): Long {
        deletePlaylistByTitle(title)
        return createPlaylist(title, sourceType, sourceTitle)
    }

    /**
     * 按来源找已存在的歌单（重复导入时复用，而不是建一堆同名歌单）。
     *
     * 比较统一走 [PlaylistNaming.normalize]；歌单如果因为重名被自动改过名，
     * 用 [StoredPlaylist.sourceTitle]（导入来源的原始标题）认亲。
     */
    fun findBySource(sourceType: PlaylistSourceType, title: String): StoredPlaylist? {
        val desired = PlaylistNaming.normalize(title) ?: return null
        return db.playlists.firstOrNull { playlist ->
            playlist.sourceType == sourceType.name &&
                PlaylistNaming.normalize(playlist.sourceTitle ?: playlist.title) == desired
        }
    }

    // ---------------- 歌单重名（语义见 shared 的 PlaylistNaming） ----------------

    /** 已有歌单的「标题 + 曲目数」，交给 [PlaylistNaming.findConflict] 判重 / 生成对话框上下文 */
    fun existingPlaylistTitles(exceptId: Long? = null): List<ExistingPlaylistTitle> {
        val counts = db.songs.groupingBy { it.playlistId }.eachCount()
        return db.playlists
            .filter { exceptId == null || it.id != exceptId }
            .map { ExistingPlaylistTitle(it.title, counts[it.id] ?: 0) }
    }

    /** 按规范化标题找歌单；[exceptId] 用于重命名时排除自己 */
    fun findByTitle(title: String, exceptId: Long? = null): StoredPlaylist? {
        val desired = PlaylistNaming.normalize(title) ?: return null
        return db.playlists.firstOrNull { playlist ->
            (exceptId == null || playlist.id != exceptId) &&
                PlaylistNaming.normalize(playlist.title) == desired
        }
    }

    fun titleExists(title: String, exceptId: Long? = null): Boolean = findByTitle(title, exceptId) != null

    /** 当前库里不重名的标题（Windows 风格：「X」→「X (2)」→「X (3)」…） */
    fun uniqueTitle(base: String): String = PlaylistNaming.uniqueTitle(base, db.playlists.map { it.title })

    /** 检查重名；重名时返回对话框需要的上下文（旧歌单曲目数、建议名） */
    fun findConflict(desired: String, exceptId: Long? = null): PlaylistNameConflict? =
        PlaylistNaming.findConflict(desired, existingPlaylistTitles(exceptId))

    /** 删除所有同名歌单（连同曲目），返回删除的歌单数量；[exceptId] 用于重命名覆盖时排除自己 */
    fun deletePlaylistByTitle(title: String, exceptId: Long? = null): Int {
        val desired = PlaylistNaming.normalize(title) ?: return 0
        return synchronized(lock) {
            val ids = db.playlists
                .filter { (exceptId == null || it.id != exceptId) && PlaylistNaming.normalize(it.title) == desired }
                .map { it.id }
                .toSet()
            if (ids.isEmpty()) {
                return@synchronized 0
            } else {
                db = db.copy(
                    playlists = db.playlists.filterNot { it.id in ids },
                    songs = db.songs.filterNot { it.playlistId in ids },
                )
                persistLocked()
                ids.size
            }
        }
    }

    /** 设置/清除歌单封面（传 null 表示恢复默认的渐变占位） */
    fun setPlaylistCover(id: Long, coverUrl: String? = null, coverPath: String? = null) = mutate { data ->
        data.copy(
            playlists = data.playlists.map {
                if (it.id == id) it.copy(coverUrl = coverUrl, coverPath = coverPath) else it
            },
        )
    }

    /** 重命名歌单；标题先规范化，重名由调用方先弹窗处理 */
    fun renamePlaylist(id: Long, title: String) {
        val normalized = PlaylistNaming.normalize(title) ?: return
        mutate {
            it.copy(playlists = it.playlists.map { p -> if (p.id == id) p.copy(title = normalized) else p })
        }
    }

    /** 覆盖式重命名：先删掉另一个同名歌单（连同曲目），再把 [id] 改成该名字 */
    fun renamePlaylistOverwriting(id: Long, title: String) {
        deletePlaylistByTitle(title, exceptId = id)
        renamePlaylist(id, title)
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
        /** 当前标题迁移版本；以后如果还要再改一次命名规则，把版本 +1 即可 */
        private const val TITLE_MIGRATION_VERSION = 1

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
