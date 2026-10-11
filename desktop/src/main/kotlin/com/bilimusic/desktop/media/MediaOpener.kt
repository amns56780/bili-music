package com.bilimusic.desktop.media

import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliClient
import com.bilimusic.app.data.remote.apiCall
import com.bilimusic.app.util.AppLog
import com.bilimusic.app.util.BiliLinkParser
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「粘贴 BV 号 / 链接直接播放」。
 *
 * 支持：BV 号、av 号、完整视频链接、b23.tv 短链（会自动跟随 302 解析）。
 * 这是 Phase 3 的可验证入口——歌单导入在 Phase 4 接入后，这里仍然保留作快捷播放。
 */
class MediaOpener(
    private val client: BiliClient,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "MediaOpener"
    }

    fun open(text: String, onMessage: (String) -> Unit = {}) {
        scope.launch {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                onMessage("请输入 BV 号或视频链接")
                return@launch
            }

            // b23.tv 短链先跟随 302 拿到真实链接
            var bvid = BiliLinkParser.parseBvid(trimmed)
            if (bvid == null) {
                val shortLink = BiliLinkParser.findShortLink(trimmed)
                if (shortLink != null) {
                    val resolved = client.shortLinkResolver.resolve(shortLink)
                    bvid = resolved?.let { BiliLinkParser.parseFromResolvedUrl(it) }
                }
            }
            if (bvid == null) {
                onMessage("没能从这段文本里认出视频（支持 BV 号 / av 号 / 视频链接 / b23.tv 短链）")
                return@launch
            }

            val signed = client.wbiSigner.sign(mapOf("bvid" to bvid))
            when (val result = apiCall { client.api.videoView(signed) }) {
                is ApiResult.Success -> {
                    val data = result.data
                    if (data.cid <= 0L) {
                        onMessage("这首拿不到音频轨道（可能是付费/番剧内容）")
                        return@launch
                    }
                    playback.playSingle(
                        Track(
                            bvid = data.bvid.ifBlank { bvid },
                            cid = data.cid,
                            title = data.title.ifBlank { bvid },
                            artist = data.owner?.name.orEmpty().ifBlank { "未知UP主" },
                            coverUrl = data.pic,
                            durationMs = data.duration * 1000L,
                        ),
                    )
                }

                is ApiResult.Error -> {
                    AppLog.w(TAG, "取视频信息失败：${result.failure.userMessage}")
                    onMessage("取视频信息失败：${result.failure.userMessage}")
                }
            }
        }
    }
}
