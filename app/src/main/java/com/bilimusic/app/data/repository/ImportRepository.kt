package com.bilimusic.app.data.repository

import com.bilimusic.app.data.local.prefs.AccountStore
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliApi
import com.bilimusic.app.data.remote.CookieStore
import com.bilimusic.app.data.remote.Failure
import com.bilimusic.app.data.remote.FailureKind
import com.bilimusic.app.data.remote.RequestThrottle
import com.bilimusic.app.data.remote.ShortLinkResolver
import com.bilimusic.app.data.remote.WbiSigner
import com.bilimusic.app.data.remote.apiCall
import com.bilimusic.app.data.remote.dto.FavMediaDto
import com.bilimusic.app.data.remote.dto.UgcEpisodeDto
import com.bilimusic.app.data.remote.dto.UgcSeasonDto
import com.bilimusic.app.data.remote.dto.ViewData
import com.bilimusic.app.data.remote.dto.ViewPageDto
import com.bilimusic.app.domain.model.AddSongResult
import com.bilimusic.app.domain.model.FavFolder
import com.bilimusic.app.domain.model.ImportProgress
import com.bilimusic.app.domain.model.ImportReport
import com.bilimusic.app.domain.model.PlaylistSourceType
import com.bilimusic.app.domain.model.SongDraft
import com.bilimusic.app.util.BiliLinkParser
import com.bilimusic.app.util.PlaylistShareCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-2 歌单导入：收藏夹 / 稍后再看 / UP主投稿 / 合集 / 手动加单曲。
 *
 * 设计要点（对齐任务书）：
 * - 导入前逐条取视频详情拿 cid、时长、封面、UP主名再落库
 * - 相邻请求间隔 400ms（[RequestThrottle]），避免触发风控
 * - 失效视频（attr != 0 / 标题「已失效视频」/ -404 / 62002 / 62004）跳过并计入报告
 * - 同一个收藏夹/合集重复导入时复用同一个歌单，靠 (bvid, cid) 唯一索引去重
 * - UP主投稿遇到 -352 / -412 不死磕，直接降级为友好提示
 */
interface ImportRepository {

    /** 拉取账号下的收藏夹列表 */
    suspend fun loadFavFolders(): ApiResult<List<FavFolder>>

    /** 收藏夹 → 每个收藏夹一个歌单 */
    fun importFavorites(folders: List<FavFolder>): Flow<ImportProgress>

    /** 稍后再看 → 一个歌单 */
    fun importToView(): Flow<ImportProgress>

    /** UP 主投稿 → 一个歌单 */
    fun importUploads(mid: Long, maxPages: Int = 10): Flow<ImportProgress>

    /** 合集 / 分P / 单曲（粘贴 BV、AV、链接）→ 导入成一个歌单 */
    fun importByVideoInput(rawInput: String): Flow<ImportProgress>

    /** 往已有歌单里加单曲（粘贴 BV / AV / 分享链接） */
    suspend fun addSongToPlaylist(playlistId: Long, rawInput: String): ApiResult<AddSongResult>
}

@Singleton
class ImportRepositoryImpl @Inject constructor(
    private val api: BiliApi,
    private val wbiSigner: WbiSigner,
    private val playlistRepository: PlaylistRepository,
    private val throttle: RequestThrottle,
    private val cookieStore: CookieStore,
    private val accountStore: AccountStore,
    private val shortLinkResolver: ShortLinkResolver,
) : ImportRepository {

    // ---------------- 收藏夹 ----------------

    override suspend fun loadFavFolders(): ApiResult<List<FavFolder>> {
        val mid = currentMid()
            ?: return ApiResult.Error(Failure("没有读到账号 UID，请重新登录后再试", null, FailureKind.AUTH))
        throttle.await()
        val params = wbiSigner.sign(mapOf("up_mid" to mid.toString()))
        return when (val result = apiCall { api.favFolderList(params) }) {
            is ApiResult.Success -> ApiResult.Success(
                result.data.list.orEmpty().map { dto ->
                    FavFolder(
                        id = dto.id,
                        title = dto.title.ifBlank { "未命名收藏夹" },
                        mediaCount = dto.mediaCount,
                        isPrivate = dto.attr != 0,
                    )
                },
            )

            is ApiResult.Error -> result
        }
    }

    override fun importFavorites(folders: List<FavFolder>): Flow<ImportProgress> = flow {
        if (folders.isEmpty()) {
            emit(ImportProgress.Failed("请先勾选要导入的收藏夹"))
            return@flow
        }
        for (folder in folders) {
            emit(ImportProgress.Preparing("正在准备歌单「${folder.title}」…"))
            val playlistId = obtainPlaylist(
                title = folder.title,
                type = PlaylistSourceType.FAV,
                sourceId = folder.id.toString(),
            )
            val counters = Counters()
            var firstCover: String? = null
            var pageNumber = 1
            var hasMore = true
            var aborted: String? = null

            while (hasMore) {
                throttle.await()
                val params = wbiSigner.sign(
                    mapOf(
                        "media_id" to folder.id.toString(),
                        "pn" to pageNumber.toString(),
                        "ps" to PAGE_SIZE.toString(),
                        "platform" to "web",
                    ),
                )
                when (val page = apiCall { api.favResourceList(params) }) {
                    is ApiResult.Error -> {
                        aborted = page.failure.userMessage
                        hasMore = false
                    }

                    is ApiResult.Success -> {
                        val data = page.data
                        val medias = data.medias.orEmpty()
                        if (medias.isEmpty()) {
                            hasMore = false
                        } else {
                            val total = (data.info?.mediaCount ?: folder.mediaCount)
                                .coerceAtLeast((pageNumber - 1) * PAGE_SIZE + medias.size)
                            medias.forEachIndexed { index, media ->
                                val current = (pageNumber - 1) * PAGE_SIZE + index + 1
                                emit(
                                    ImportProgress.Working(
                                        current = current,
                                        total = total,
                                        message = media.title.ifBlank { "第 $current 条" },
                                    ),
                                )
                                val cover = handleFavMedia(playlistId, media, counters)
                                if (firstCover == null && cover != null) firstCover = cover
                            }
                            hasMore = data.hasMore
                            pageNumber++
                        }
                    }
                }
            }

            finishPlaylist(playlistId, firstCover)
            emit(ImportProgress.Finished(counters.toReport(playlistId, folder.title)))
            if (aborted != null) {
                emit(ImportProgress.Failed("「${folder.title}」导入中断：$aborted（已导入的部分保留）"))
            }
        }
    }

    /** 处理收藏夹里的一条：返回封面（用于歌单封面）；失效/失败返回 null 并计入统计 */
    private suspend fun handleFavMedia(
        playlistId: Long,
        media: FavMediaDto,
        counters: Counters,
    ): String? {
        if (isInvalidFavMedia(media)) {
            counters.invalid++
            return null
        }
        if (media.bvid.isBlank()) {
            counters.invalid++
            return null
        }
        return when (val detail = fetchDetail(media.bvid)) {
            is DetailResult.Ok -> {
                val draft = detail.data.toDraft()
                when (addSong(playlistId, draft)) {
                    AddSongsOutcome.Inserted -> counters.success++
                    AddSongsOutcome.Duplicate -> counters.duplicate++
                }
                draft.coverUrl
            }

            DetailResult.Invalid -> {
                counters.invalid++
                null
            }

            is DetailResult.Failed -> {
                counters.failed++
                null
            }
        }
    }

    /** 收藏夹列表里已经能判定失效的条目 */
    private fun isInvalidFavMedia(media: FavMediaDto): Boolean =
        media.attr != 0 || media.title.contains("已失效")

    // ---------------- 稍后再看 ----------------

    override fun importToView(): Flow<ImportProgress> = flow {
        emit(ImportProgress.Preparing("正在读取稍后再看列表…"))
        throttle.await()
        when (val list = apiCall { api.toView() }) {
            is ApiResult.Error -> emit(ImportProgress.Failed(list.failure.userMessage))

            is ApiResult.Success -> {
                val items = list.data.list.orEmpty()
                if (items.isEmpty()) {
                    emit(ImportProgress.Failed("稍后再看是空的，先去 B 站加几个视频吧"))
                    return@flow
                }
                val playlistId = obtainPlaylist("稍后再看", PlaylistSourceType.TOVIEW, "toview")
                val counters = Counters()
                var firstCover: String? = null
                items.forEachIndexed { index, item ->
                    emit(
                        ImportProgress.Working(
                            current = index + 1,
                            total = items.size,
                            message = item.title.ifBlank { "第 ${index + 1} 条" },
                        ),
                    )
                    if (item.bvid.isBlank()) {
                        counters.invalid++
                        return@forEachIndexed
                    }
                    when (val detail = fetchDetail(item.bvid)) {
                        is DetailResult.Ok -> {
                            val draft = detail.data.toDraft()
                            when (addSong(playlistId, draft)) {
                                AddSongsOutcome.Inserted -> counters.success++
                                AddSongsOutcome.Duplicate -> counters.duplicate++
                            }
                            if (firstCover == null) firstCover = draft.coverUrl
                        }

                        DetailResult.Invalid -> counters.invalid++
                        is DetailResult.Failed -> counters.failed++
                    }
                }
                finishPlaylist(playlistId, firstCover)
                emit(ImportProgress.Finished(counters.toReport(playlistId, "稍后再看")))
            }
        }
    }

    // ---------------- UP 主投稿（高风险） ----------------

    override fun importUploads(mid: Long, maxPages: Int): Flow<ImportProgress> = flow {
        emit(ImportProgress.Preparing("正在读取 UP 主投稿列表…"))
        val playlistTitle = "UP主投稿 $mid"
        var playlistId: Long? = null
        val counters = Counters()
        var firstCover: String? = null

        for (pageNumber in 1..maxPages) {
            throttle.await()
            val params = wbiSigner.sign(
                mapOf(
                    "mid" to mid.toString(),
                    "pn" to pageNumber.toString(),
                    "ps" to "30",
                    "order" to "pubdate",
                    "platform" to "web",
                    "web_location" to "1550101",
                ),
            )
            when (val page = apiCall { api.spaceArcSearch(params) }) {
                is ApiResult.Error -> {
                    // 任务书：-352 / -412 不要死磕，降级为友好提示
                    val riskControlled = page.failure.kind == FailureKind.RISK_CONTROL
                    val friendly = if (riskControlled) {
                        "该来源受 B 站风控限制，请改用收藏夹或合集导入"
                    } else {
                        page.failure.userMessage
                    }
                    playlistId?.let {
                        finishPlaylist(it, firstCover)
                        emit(ImportProgress.Finished(counters.toReport(it, playlistTitle)))
                    }
                    emit(ImportProgress.Failed(friendly, riskControlled = riskControlled))
                    return@flow
                }

                is ApiResult.Success -> {
                    val data = page.data
                    val list = data.list?.vlist.orEmpty()
                    if (list.isEmpty()) break
                    val pid = playlistId
                        ?: obtainPlaylist(playlistTitle, PlaylistSourceType.UPLOAD, mid.toString())
                            .also { playlistId = it }
                    val total = (data.page?.count ?: list.size).coerceAtLeast(list.size)

                    list.forEachIndexed { index, item ->
                        emit(
                            ImportProgress.Working(
                                current = (pageNumber - 1) * 30 + index + 1,
                                total = total,
                                message = item.title.ifBlank { "第 ${index + 1} 条" },
                            ),
                        )
                        if (item.bvid.isBlank()) {
                            counters.invalid++
                            return@forEachIndexed
                        }
                        when (val detail = fetchDetail(item.bvid)) {
                            is DetailResult.Ok -> {
                                val draft = detail.data.toDraft()
                                when (addSong(pid, draft)) {
                                    AddSongsOutcome.Inserted -> counters.success++
                                    AddSongsOutcome.Duplicate -> counters.duplicate++
                                }
                                if (firstCover == null) firstCover = draft.coverUrl
                            }

                            DetailResult.Invalid -> counters.invalid++
                            is DetailResult.Failed -> counters.failed++
                        }
                    }
                    if (list.size < 30) break
                }
            }
        }

        val pid = playlistId
        if (pid == null) {
            emit(ImportProgress.Failed("没有读到任何投稿（可能是隐私设置，或该账号没有公开投稿）"))
        } else {
            finishPlaylist(pid, firstCover)
            emit(ImportProgress.Finished(counters.toReport(pid, playlistTitle)))
        }
    }

    // ---------------- 合集 / 分P / 单曲 ----------------

    override fun importByVideoInput(rawInput: String): Flow<ImportProgress> = flow {
        emit(ImportProgress.Preparing("正在解析输入内容…"))

        // 歌单分享链接（bm://pl/v1/…）：解码出 bvid 列表后整单导入。
        // 协议见 shared 的 PlaylistShareCodec，和桌面端共用同一份编解码。
        val shared = PlaylistShareCodec.decode(rawInput)
        if (shared != null) {
            val title = shared.t.ifBlank { "分享的歌单" }
            emit(ImportProgress.Preparing("正在导入「" + title + "」…"))
            val playlistId = obtainPlaylist(title, PlaylistSourceType.MANUAL, "share:" + title)
            val counters = Counters()
            var firstCover: String? = null
            shared.s.forEachIndexed { index, bvid ->
                emit(
                    ImportProgress.Working(
                        current = index + 1,
                        total = shared.s.size,
                        message = "第 " + (index + 1) + " 首 · " + bvid,
                    ),
                )
                when (val detail = fetchDetail(bvid)) {
                    is DetailResult.Ok -> {
                        val draft = detail.data.toDraft()
                        when (addSong(playlistId, draft)) {
                            AddSongsOutcome.Inserted -> counters.success++
                            AddSongsOutcome.Duplicate -> counters.duplicate++
                        }
                        if (firstCover == null) firstCover = draft.coverUrl
                    }

                    DetailResult.Invalid -> counters.invalid++
                    is DetailResult.Failed -> counters.failed++
                }
            }
            finishPlaylist(playlistId, firstCover)
            emit(ImportProgress.Finished(counters.toReport(playlistId, title)))
            return@flow
        }

        val ref = resolveVideoRef(rawInput)
        if (ref == null) {
            emit(ImportProgress.Failed("没认出 BV 号 / AV 号 / 链接，请检查粘贴内容"))
            return@flow
        }
        emit(ImportProgress.Preparing("正在读取视频信息…"))
        val detail = when (val result = fetchDetailRaw(ref)) {
            is DetailResult.Ok -> result.data
            DetailResult.Invalid -> {
                emit(ImportProgress.Failed("视频不存在或已失效"))
                return@flow
            }

            is DetailResult.Failed -> {
                emit(ImportProgress.Failed(result.message))
                return@flow
            }
        }

        val season = detail.ugcSeason
        val pages = detail.pages.orEmpty()
        val hasSeason = season != null && !season.sections.isNullOrEmpty()
        val drafts: List<SongDraft>
        val playlistTitle: String
        val sourceId: String
        when {
            hasSeason && season != null -> {
                drafts = buildSeasonDrafts(season)
                playlistTitle = season.title.ifBlank { detail.title }
                sourceId = season.id.toString()
            }

            pages.size > 1 -> {
                drafts = pages.map { it.toDraft(detail) }
                playlistTitle = detail.title
                sourceId = "pages:${detail.bvid}"
            }

            else -> {
                drafts = listOf(detail.toDraft())
                playlistTitle = detail.title
                sourceId = "video:${detail.bvid}"
            }
        }
        if (drafts.isEmpty()) {
            emit(ImportProgress.Failed("这个视频既没有分P也没有合集内容"))
            return@flow
        }

        val playlistId = obtainPlaylist(playlistTitle, PlaylistSourceType.SEASON, sourceId)
        val counters = Counters()
        var firstCover: String? = null
        drafts.forEachIndexed { index, draft ->
            emit(
                ImportProgress.Working(
                    current = index + 1,
                    total = drafts.size,
                    message = draft.title,
                ),
            )
            when (addSong(playlistId, draft)) {
                AddSongsOutcome.Inserted -> counters.success++
                AddSongsOutcome.Duplicate -> counters.duplicate++
            }
            if (firstCover == null) firstCover = draft.coverUrl
        }
        finishPlaylist(playlistId, firstCover)
        emit(ImportProgress.Finished(counters.toReport(playlistId, playlistTitle)))
    }

    // ---------------- 手动加单曲 ----------------

    override suspend fun addSongToPlaylist(
        playlistId: Long,
        rawInput: String,
    ): ApiResult<AddSongResult> {
        val ref = resolveVideoRef(rawInput)
            ?: return ApiResult.Error(
                Failure("没认出 BV 号 / AV 号 / 链接，请检查粘贴内容", null, FailureKind.UNKNOWN),
            )
        return when (val detail = fetchDetailRaw(ref)) {
            DetailResult.Invalid -> ApiResult.Error(
                Failure("视频不存在或已失效，无法添加", null, FailureKind.NOT_FOUND),
            )

            is DetailResult.Failed -> ApiResult.Error(
                Failure(detail.message, null, FailureKind.NETWORK),
            )

            is DetailResult.Ok -> {
                val draft = detail.data.toDraft()
                when (addSong(playlistId, draft)) {
                    AddSongsOutcome.Inserted -> ApiResult.Success(
                        AddSongResult(
                            added = true,
                            message = "已添加「${draft.title}」",
                            songTitle = draft.title,
                        ),
                    )

                    AddSongsOutcome.Duplicate -> ApiResult.Success(
                        AddSongResult(
                            added = false,
                            message = "这首歌已经在歌单里了：${draft.title}",
                            songTitle = draft.title,
                        ),
                    )
                }
            }
        }
    }

    // ---------------- 内部工具 ----------------

    private suspend fun obtainPlaylist(
        title: String,
        type: PlaylistSourceType,
        sourceId: String,
    ): Long {
        val existing = playlistRepository.findPlaylistBySource(type, sourceId)
        if (existing != null) return existing.id
        return playlistRepository.createPlaylistForSource(title, type, sourceId)
    }

    private suspend fun finishPlaylist(playlistId: Long, coverUrl: String?) {
        playlistRepository.updatePlaylistCover(playlistId, coverUrl)
        playlistRepository.markImported(playlistId)
    }

    private suspend fun addSong(playlistId: Long, draft: SongDraft): AddSongsOutcome {
        val result = playlistRepository.addSongs(playlistId, listOf(draft))
        return if (result.inserted > 0) AddSongsOutcome.Inserted else AddSongsOutcome.Duplicate
    }

    private suspend fun fetchDetail(bvid: String): DetailResult =
        fetchDetailRaw(VideoRef(bvid = bvid))

    private suspend fun fetchDetailRaw(ref: VideoRef): DetailResult {
        val params = when {
            !ref.bvid.isNullOrBlank() -> mapOf("bvid" to ref.bvid)
            ref.aid != null -> mapOf("aid" to ref.aid.toString())
            else -> return DetailResult.Failed("缺少 BV 号 / AV 号")
        }
        throttle.await()
        return when (val result = apiCall(maxAttempts = 2) { api.videoView(params) }) {
            is ApiResult.Success -> DetailResult.Ok(result.data)

            is ApiResult.Error -> when (result.failure.kind) {
                FailureKind.NOT_FOUND -> DetailResult.Invalid
                else -> DetailResult.Failed(result.failure.userMessage)
            }
        }
    }

    private suspend fun resolveVideoRef(rawInput: String): VideoRef? {
        val text = rawInput.trim()
        if (text.isEmpty()) return null
        BiliLinkParser.parseBvid(text)?.let { return VideoRef(bvid = it) }
        BiliLinkParser.parseAid(text)?.let { return VideoRef(aid = it) }
        val shortLink = BiliLinkParser.findShortLink(text) ?: return null
        val resolved = shortLinkResolver.resolve(shortLink) ?: return null
        BiliLinkParser.parseBvid(resolved)?.let { return VideoRef(bvid = it) }
        BiliLinkParser.parseAid(resolved)?.let { return VideoRef(aid = it) }
        return null
    }

    /**
     * 账号 mid：优先用登录时保存的账号快照（手动 Cookie 登录可能只有 SESSDATA，没有 DedeUserID），
     * 兜底再读 Cookie 里的 DedeUserID。
     */
    private fun currentMid(): Long? {
        val snapshotMid = accountStore.load()?.mid
        if (snapshotMid != null && snapshotMid > 0L) return snapshotMid
        return cookieStore.dedeUserId?.toLongOrNull()
    }

    /** 合集 → 每集一条；顺序、集数、第几集都按原始顺序算好，供队列与选集页使用 */
    private fun buildSeasonDrafts(season: UgcSeasonDto): List<SongDraft> {
        val seasonKey = "season:${season.id}"
        val total = season.sections.orEmpty().sumOf { it.episodes?.size ?: 0 }
        val drafts = mutableListOf<SongDraft>()
        var index = 0
        season.sections.orEmpty().forEach { section ->
            section.episodes.orEmpty().forEach { episode ->
                index++
                drafts += episode.toDraft(seasonKey = seasonKey, seasonCount = total, pageIndex = index)
            }
        }
        return drafts
    }

    /** 把 ViewData 转成待落库曲目；分P/合集信息一并带上，供选集页使用 */
    private fun ViewData.toDraft(): SongDraft {
        val season = ugcSeason
        val pageCount = pages?.size ?: 0
        val hasSeason = season != null && !season.sections.isNullOrEmpty()
        val collectionKey = when {
            hasSeason && season != null -> "season:${season.id}"
            pageCount > 1 -> "pages:$bvid"
            else -> null
        }
        val episodeCount = when {
            hasSeason && season != null -> season.sections.orEmpty().sumOf { it.episodes?.size ?: 0 }
            else -> pageCount
        }
        val pageIndex = when {
            hasSeason && season != null -> seasonIndexOf(season, bvid) ?: 1
            else -> 1
        }
        return SongDraft(
            bvid = bvid,
            cid = cid,
            title = title,
            upperName = owner?.name.orEmpty().ifBlank { "未知UP主" },
            coverUrl = pic.toHttpsOrNull(),
            durationMs = duration * 1000L,
            collectionKey = collectionKey,
            episodeCount = episodeCount,
            pageIndex = pageIndex,
        )
    }

    private fun UgcEpisodeDto.toDraft(
        seasonKey: String,
        seasonCount: Int,
        pageIndex: Int,
    ): SongDraft {
        val arcTitle = arc?.title.orEmpty()
        return SongDraft(
            bvid = arc?.bvid?.ifBlank { bvid } ?: bvid,
            cid = cid,
            title = arcTitle.ifBlank { title },
            upperName = "未知UP主",
            coverUrl = (arc?.pic ?: pic).toHttpsOrNull(),
            durationMs = (arc?.duration ?: duration).toLong() * 1000L,
            collectionKey = seasonKey,
            episodeCount = seasonCount,
            pageIndex = pageIndex,
        )
    }

    private fun ViewPageDto.toDraft(view: ViewData): SongDraft = SongDraft(
        bvid = view.bvid,
        cid = cid,
        title = if (part.isBlank()) view.title else "${view.title} - $part",
        upperName = view.owner?.name.orEmpty().ifBlank { "未知UP主" },
        coverUrl = view.pic.toHttpsOrNull(),
        durationMs = duration * 1000L,
        collectionKey = "pages:${view.bvid}",
        episodeCount = view.pages?.size ?: 1,
        pageIndex = page,
    )

    private fun seasonIndexOf(season: UgcSeasonDto, bvid: String): Int? {
        var index = 0
        season.sections.orEmpty().forEach { section ->
            section.episodes.orEmpty().forEach { episode ->
                index++
                val episodeBvid = episode.arc?.bvid?.ifBlank { episode.bvid } ?: episode.bvid
                if (episodeBvid == bvid) return index
            }
        }
        return null
    }

    /** 封面统一转 https：Android 9+ 默认禁止明文 HTTP，不转会导致封面加载不出来 */
    private fun String?.toHttpsOrNull(): String? {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            else -> value
        }
    }

    private data class VideoRef(val bvid: String? = null, val aid: Long? = null)

    private sealed interface DetailResult {
        data class Ok(val data: ViewData) : DetailResult
        data object Invalid : DetailResult
        data class Failed(val message: String) : DetailResult
    }

    private enum class AddSongsOutcome { Inserted, Duplicate }

    private class Counters {
        var success = 0
        var invalid = 0
        var duplicate = 0
        var failed = 0

        fun toReport(playlistId: Long, title: String) = ImportReport(
            playlistId = playlistId,
            playlistTitle = title,
            success = success,
            skippedInvalid = invalid,
            skippedDuplicate = duplicate,
            failed = failed,
        )
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
