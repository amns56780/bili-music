package com.bilimusic.desktop.data

import com.bilimusic.app.data.player.StreamUrlRepository
import com.bilimusic.app.util.AppLog
import com.bilimusic.desktop.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class DownloadRecord(
    val bvid: String,
    val cid: Long,
    val title: String,
    val artist: String = "",
    val path: String,
    val bytes: Long = 0L,
    val at: Long = System.currentTimeMillis(),
)

data class DownloadUiState(
    val busy: Boolean = false,
    val currentTitle: String = "",
    val progress: Float = 0f,
    val message: String? = null,
    val records: List<DownloadRecord> = emptyList(),
)

/**
 * 下载 / 离线缓存（FR-9 桌面版）。
 *
 * 下载的是**取流后的音频文件**（m4a/aac 等），存到 `~/.bilimusic/downloads/`，
 * 并记录在 `downloads.json` 里。播放时如果发现已下载，就直接播本地文件
 * —— 与安卓端「离线可播」的语义一致。
 */
class DownloadService(
    private val client: OkHttpClient,
    private val streamUrls: StreamUrlRepository,
    private val scope: CoroutineScope,
    private val dir: File = defaultDir(),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {

    private val indexFile = File(dir, "downloads.json")

    private val _state = MutableStateFlow(
        DownloadUiState(records = readIndex()),
    )
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    /**
     * 流缓存目录：播放前把整首下载到这里，播本地文件就不会被网络抖动打断。
     * 与 downloads/ 分开：这里是"听过就缓存"，不进下载列表、不占用户可见的下载记录。
     */
    private val cacheDir = File(dir.parentFile ?: dir, "stream-cache")

    /** 流缓存文件（没有则 null） */
    fun cacheFileFor(bvid: String, cid: Long): File? {
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(bvid + "-" + cid + ".") && f.isFile && f.length() > 0) return f
        }
        return null
    }

    /**
     * 播放用：把一首歌完整下载到流缓存（已缓存/已下载则直接返回路径）。
     * 失败返回 null，调用方回退到流式播放。
     * 不写 downloads.json、不写下载 UI 状态 —— 它只是播放侧的预取。
     */
    suspend fun fetchToCache(track: Track): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        track.localPath?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        localFileFor(track.bvid, track.cid)?.let { return@withContext it.absolutePath }
        cacheFileFor(track.bvid, track.cid)?.let { return@withContext it.absolutePath }
        runCatching {
            val stream = streamUrls.resolve(track.bvid, track.cid) ?: return@withContext null
            val ext = when {
                stream.mimeType.contains("flac", true) -> "flac"
                stream.mimeType.contains("aac", true) -> "aac"
                else -> "m4a"
            }
            cacheDir.mkdirs()
            val target = File(cacheDir, track.bvid + "-" + track.cid + "." + ext)
            val tmp = File(cacheDir, target.name + ".part")
            val request = Request.Builder().url(stream.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = response.body ?: return@use
                tmp.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return@withContext null
            target.absolutePath
        }.getOrNull()
    }

    /** 已下载的本地文件（没有则 null） */
    fun localFileFor(bvid: String, cid: Long): File? {
        val record = _state.value.records.firstOrNull { it.bvid == bvid && it.cid == cid } ?: return null
        val file = File(record.path)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** 下载一首（已下载过则跳过） */
    fun download(track: Track) {
        if (track.isLocal) {
            _state.value = _state.value.copy(message = "「${track.title}」已经是本地文件了")
            return
        }
        if (localFileFor(track.bvid, track.cid) != null) {
            _state.value = _state.value.copy(message = "「${track.title}」已经下载过了")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(busy = true, currentTitle = track.title, progress = 0f, message = null)
            try {
                val stream = withContext(Dispatchers.IO) { streamUrls.resolve(track.bvid, track.cid) }
                if (stream == null) {
                    _state.value = _state.value.copy(busy = false, message = "下载失败：取流失败（${track.title}）")
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    downloadToFile(stream.url, track, stream.mimeType)
                }
                if (file == null) {
                    _state.value = _state.value.copy(busy = false, message = "下载失败：${track.title}")
                    return@launch
                }
                val record = DownloadRecord(
                    bvid = track.bvid,
                    cid = track.cid,
                    title = track.title,
                    artist = track.artist,
                    path = file.absolutePath,
                    bytes = file.length(),
                )
                val records = _state.value.records.filterNot { it.bvid == track.bvid && it.cid == track.cid } + record
                writeIndex(records)
                _state.value = DownloadUiState(
                    records = records,
                    message = "已下载「${track.title}」（${file.length() / 1024 / 1024} MB）",
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "下载出错：${track.title}", t)
                _state.value = _state.value.copy(busy = false, message = "下载出错：${t.message}")
            }
        }
    }

    /** 删除已下载文件 */
    fun delete(record: DownloadRecord) {
        runCatching { File(record.path).delete() }
        val records = _state.value.records - record
        writeIndex(records)
        _state.value = _state.value.copy(records = records, message = "已删除「${record.title}」")
    }

    private fun downloadToFile(url: String, track: Track, mimeType: String): File? {
        dir.mkdirs()
        val ext = when {
            mimeType.contains("mp4", true) || mimeType.contains("m4a", true) -> "m4a"
            mimeType.contains("flac", true) -> "flac"
            mimeType.contains("aac", true) -> "aac"
            else -> "m4a"
        }
        val target = File(dir, "${track.bvid}-${track.cid}.$ext")
        val tmp = File(dir, target.name + ".part")
        return runCatching {
            // 复用同一个 OkHttpClient：Referer/UA 已由拦截器注入，否则会 403
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val total = body.contentLength()
                tmp.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                _state.value = _state.value.copy(progress = (read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return@runCatching null
            target
        }.getOrNull()
    }

    private fun readIndex(): List<DownloadRecord> = runCatching {
        if (!indexFile.isFile) return@runCatching emptyList()
        json.decodeFromString<List<DownloadRecord>>(indexFile.readText())
    }.getOrElse { emptyList() }

    private fun writeIndex(records: List<DownloadRecord>) {
        runCatching {
            dir.mkdirs()
            indexFile.writeText(json.encodeToString(records))
        }
    }

    companion object {
        private const val TAG = "DownloadService"

        fun defaultDir(): File = File(File(System.getProperty("user.home"), ".bilimusic"), "downloads")
    }
}
