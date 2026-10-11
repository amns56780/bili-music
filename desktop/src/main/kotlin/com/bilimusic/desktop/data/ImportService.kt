package com.bilimusic.desktop.data

import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliClient
import com.bilimusic.app.data.remote.apiCall
import com.bilimusic.app.data.remote.dto.FavFolderDto
import com.bilimusic.app.domain.model.PlaylistSourceType
import com.bilimusic.app.util.AppLog
import com.bilimusic.app.util.BiliLinkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 导入进度（UI 用来显示「正在导入 x/y」） */
data class ImportProgress(
    val running: Boolean = false,
    val text: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/**
 * 歌单导入（FR-2）：收藏夹 / 稍后再看。
 *
 * 说明：收藏夹接口返回的条目**没有 cid**，而取流必须带 cid，所以导入时逐条调
 * `view` 接口补齐，并用共享层的 [com.bilimusic.app.data.remote.RequestThrottle]
 * 做 400ms 节流（任务书 4.6，避免触发风控）。稍后再看接口自带 cid，可秒导。
 *
 * UP主投稿 / 合集两个来源在下一阶段接入（接口已在 BiliApi 里）。
 */
class ImportService(
    private val client: BiliClient,
    private val store: PlaylistStore,
    private val scope: CoroutineScope,
) {

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private companion object {
        const val TAG = "DesktopImport"
        const val PAGE_SIZE = 20
        /** 单次导入的翻页上限，防止误点把几千首一次拉完 */
        const val MAX_PAGES = 50
    }

    fun consumeError() {
        _progress.value = _progress.value.copy(error = null)
    }

    /** 拉取「我创建的收藏夹」列表；调用方传入登录账号的 mid */
    fun loadFavFolders(mid: Long, onResult: (List<FavFolderDto>) -> Unit) {
        scope.launch {
            val signed = client.wbiSigner.sign(mapOf("up_mid" to mid.toString()))
            when (val result = apiCall { client.api.favFolderList(signed) }) {
                is ApiResult.Success -> onResult(result.data.list.orEmpty())
                is ApiResult.Error -> {
                    AppLog.w(TAG, "收藏夹列表获取失败：${result.failure.userMessage}")
                    _progress.value = _progress.value.copy(error = result.failure.userMessage)
                }
            }
        }
    }

    fun importFavFolder(folderId: Long, folderTitle: String) {
        scope.launch {
            _progress.value = ImportProgress(running = true, text = "正在导入「$folderTitle」…")
            val playlistId = store.findBySource(PlaylistSourceType.FAV, folderTitle)?.id
                ?: store.createPlaylist(folderTitle, PlaylistSourceType.FAV)

            val collected = mutableListOf<NewSong>()
            var page = 1
            var hasMore = true
            while (hasMore && page <= MAX_PAGES) {
                val signed = client.wbiSigner.sign(
                    mapOf(
                        "media_id" to folderId.toString(),
                        "pn" to page.toString(),
                        "ps" to PAGE_SIZE.toString(),
                        "platform" to "web",
                    ),
                )
                client.requestThrottle.await()
                val result = apiCall { client.api.favResourceList(signed) }
                if (result is ApiResult.Error) {
                    _progress.value = _progress.value.copy(
                        running = false,
                        error = "导入中断：${result.failure.userMessage}",
                    )
                    return@launch
                }
                val data = (result as ApiResult.Success).data
                val medias = data.medias.orEmpty()
                collected += medias.mapNotNull { media ->
                    if (media.bvid.isBlank()) return@mapNotNull null
                    NewSong(
                        bvid = media.bvid,
                        // 收藏夹接口不给 cid，先占位，下面补齐
                        cid = 0L,
                        title = media.title,
                        artist = media.upper?.name.orEmpty(),
                        coverUrl = media.cover,
                        durationMs = media.duration * 1000L,
                    )
                }
                _progress.value = _progress.value.copy(
                    text = "正在导入「$folderTitle」：已读取 ${collected.size} 首",
                    current = collected.size,
                    total = data.info?.mediaCount ?: collected.size,
                )
                hasMore = data.hasMore
                page++
            }

            val withCid = resolveCids(collected, folderTitle)
            val added = store.addSongs(playlistId, withCid)
            _progress.value = ImportProgress(
                text = "「$folderTitle」导入完成：新增 $added 首" +
                    if (withCid.size < collected.size) "，${collected.size - withCid.size} 首取不到音频轨道已跳过" else "",
            )
        }
    }

    fun importToView() {
        scope.launch {
            val title = "稍后再看"
            _progress.value = ImportProgress(running = true, text = "正在导入「$title」…")
            val playlistId = store.findBySource(PlaylistSourceType.TOVIEW, title)?.id
                ?: store.createPlaylist(title, PlaylistSourceType.TOVIEW)

            val signed = client.wbiSigner.sign(mapOf("access_key" to "", "pn" to "1", "ps" to "50"))
            when (val result = apiCall { client.api.toView() }) {
                is ApiResult.Success -> {
                    val songs = result.data.list.orEmpty().mapNotNull { item ->
                        if (item.bvid.isBlank() || item.cid <= 0L) return@mapNotNull null
                        NewSong(
                            bvid = item.bvid,
                            cid = item.cid,
                            title = item.title,
                            artist = item.owner?.name.orEmpty(),
                            coverUrl = item.pic,
                            durationMs = item.duration * 1000L,
                        )
                    }
                    val added = store.addSongs(playlistId, songs)
                    _progress.value = ImportProgress(text = "「$title」导入完成：新增 $added 首")
                }

                is ApiResult.Error -> _progress.value = ImportProgress(
                    error = "「$title」导入失败：${result.failure.userMessage}",
                )
            }
        }
    }

    /** 导入 UP 主投稿（粘贴 UP 主主页链接或 UID） */
    fun importSpaceUploads(mid: Long, uname: String = "") {
        scope.launch {
            val name = uname.ifBlank { "UP主 $mid" }
            _progress.value = ImportProgress(running = true, text = "正在导入「$name」的投稿…")
            val playlistId = store.findBySource(PlaylistSourceType.UPLOAD, name)?.id
                ?: store.createPlaylist(name, PlaylistSourceType.UPLOAD)

            val collected = mutableListOf<NewSong>()
            var page = 1
            var total = 0
            while (page <= MAX_PAGES) {
                val signed = client.wbiSigner.sign(
                    mapOf(
                        "mid" to mid.toString(),
                        "pn" to page.toString(),
                        "ps" to "30",
                        "order" to "pubdate",
                        "platform" to "web",
                        "web_location" to "1550101",
                    ),
                )
                client.requestThrottle.await()
                when (val result = apiCall { client.api.spaceArcSearch(signed) }) {
                    is ApiResult.Success -> {
                        val data = result.data
                        total = data.page?.count ?: total
                        val vlist = data.list?.vlist.orEmpty()
                        if (vlist.isEmpty()) break
                        collected += vlist.mapNotNull { item ->
                            if (item.bvid.isBlank()) return@mapNotNull null
                            NewSong(
                                bvid = item.bvid,
                                // 投稿列表接口不给 cid，先占位，后面统一补齐
                                cid = 0L,
                                title = item.title,
                                artist = name,
                                coverUrl = item.pic,
                                durationMs = BiliLinkParser.parseDurationText(item.length),
                            )
                        }
                        _progress.value = _progress.value.copy(
                            text = "正在导入「$name」：已读取 ${collected.size}/${if (total > 0) total else collected.size}",
                            current = collected.size,
                            total = total,
                        )
                        page++
                    }

                    is ApiResult.Error -> {
                        _progress.value = ImportProgress(
                            error = "UP主投稿导入失败：${result.failure.userMessage}",
                        )
                        return@launch
                    }
                }
            }

            val withCid = resolveCids(collected, name)
            val added = store.addSongs(playlistId, withCid)
            _progress.value = ImportProgress(
                text = "「$name」导入完成：新增 $added 首" +
                    if (withCid.size < collected.size) "，${collected.size - withCid.size} 首取不到音频轨道已跳过" else "",
            )
        }
    }

    /** 导入合集 / 系列（粘贴合集链接或 season_id） */
    fun importSeason(seasonId: Long, title: String) {
        scope.launch {
            val name = title.ifBlank { "合集 $seasonId" }
            _progress.value = ImportProgress(running = true, text = "正在导入合集「$name」…")
            val playlistId = store.findBySource(PlaylistSourceType.SEASON, name)?.id
                ?: store.createPlaylist(name, PlaylistSourceType.SEASON)

            val songs = mutableListOf<NewSong>()
            var page = 1
            var total = 0
            while (page <= MAX_PAGES) {
                val signed = client.wbiSigner.sign(
                    mapOf(
                        "season_id" to seasonId.toString(),
                        "page_num" to page.toString(),
                        "page_size" to "30",
                    ),
                )
                client.requestThrottle.await()
                when (val result = apiCall { client.api.seasonArchives(signed) }) {
                    is ApiResult.Success -> {
                        val data = result.data
                        total = data.page?.total ?: total
                        val archives = data.archives.orEmpty()
                        if (archives.isEmpty()) break
                        songs += archives.mapNotNull { item ->
                            // 合集接口自带 cid，不需要逐条补齐
                            if (item.bvid.isBlank() || item.cid <= 0L) return@mapNotNull null
                            NewSong(
                                bvid = item.bvid,
                                cid = item.cid,
                                title = item.title,
                                artist = name,
                                coverUrl = item.pic,
                                durationMs = item.duration * 1000L,
                            )
                        }
                        _progress.value = _progress.value.copy(
                            text = "正在导入合集「$name」：${songs.size}/${if (total > 0) total else songs.size}",
                            current = songs.size,
                            total = total,
                        )
                        page++
                    }

                    is ApiResult.Error -> {
                        _progress.value = ImportProgress(
                            error = "合集导入失败：${result.failure.userMessage}",
                        )
                        return@launch
                    }
                }
            }

            val added = store.addSongs(playlistId, songs)
            _progress.value = ImportProgress(text = "合集「$name」导入完成：新增 $added 首")
        }
    }

    /** 逐条补齐 cid（带节流与进度） */
    private suspend fun resolveCids(songs: List<NewSong>, title: String): List<NewSong> {
        val out = mutableListOf<NewSong>()
        songs.forEachIndexed { index, song ->
            if (song.cid > 0L) {
                out += song
                return@forEachIndexed
            }
            _progress.value = _progress.value.copy(
                text = "正在补齐音频信息「$title」：${index + 1}/${songs.size}",
                current = index + 1,
                total = songs.size,
            )
            client.requestThrottle.await()
            val signed = client.wbiSigner.sign(mapOf("bvid" to song.bvid))
            when (val result = apiCall(maxAttempts = 1) { client.api.videoView(signed) }) {
                is ApiResult.Success -> {
                    val cid = result.data.cid
                    if (cid > 0L) {
                        out += song.copy(
                            cid = cid,
                            title = song.title.ifBlank { result.data.title },
                            coverUrl = song.coverUrl ?: result.data.pic,
                        )
                    } else {
                        AppLog.w(TAG, "跳过没有音频轨道的条目：${song.bvid}")
                    }
                }

                is ApiResult.Error -> AppLog.w(TAG, "取 ${song.bvid} 信息失败：${result.failure.userMessage}")
            }
        }
        return out
    }
}
