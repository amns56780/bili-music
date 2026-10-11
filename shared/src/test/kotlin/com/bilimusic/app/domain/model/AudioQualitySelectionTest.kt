package com.bilimusic.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-10 音质选择策略单测（纯逻辑）。
 * 关键要求：任何情况下都必须能挑出一档能播的，不能因为要不到高音质就播放失败。
 */
class AudioQualitySelectionTest {

    private fun track(id: Int, bandwidth: Long) = DashAudioTrack(
        id = id,
        baseUrl = "https://example.com/$id.m4s",
        backupUrls = emptyList(),
        bandwidth = bandwidth,
        mimeType = "audio/mp4",
        codecs = "mp4a.40.2",
    )

    private val fullSet = listOf(
        track(30216, 64_000),
        track(30232, 132_000),
        track(30280, 192_000),
    )

    @Test
    fun `自动档优先 192K`() {
        assertEquals(30280, selectAudioTrack(fullSet, AudioQualityOption.AUTO)?.id)
    }

    @Test
    fun `自动档没有 192K 时降到 132K`() {
        val tracks = listOf(track(30216, 64_000), track(30232, 132_000))
        assertEquals(30232, selectAudioTrack(tracks, AudioQualityOption.AUTO)?.id)
    }

    @Test
    fun `自动档只剩 64K 也能播`() {
        val tracks = listOf(track(30216, 64_000))
        assertEquals(30216, selectAudioTrack(tracks, AudioQualityOption.AUTO)?.id)
    }

    @Test
    fun `指定档位可用时精确命中`() {
        assertEquals(30232, selectAudioTrack(fullSet, AudioQualityOption.Q132)?.id)
        assertEquals(30216, selectAudioTrack(fullSet, AudioQualityOption.Q64)?.id)
    }

    @Test
    fun `指定 Hi-Res 但视频没有时自动降级到最高可用档`() {
        val picked = selectAudioTrack(fullSet, AudioQualityOption.HIRES)
        assertEquals(30280, picked?.id)
    }

    @Test
    fun `指定杜比但只有杜比时也能选出来`() {
        val tracks = listOf(track(30250, 448_000))
        assertEquals(30250, selectAudioTrack(tracks, AudioQualityOption.DOLBY)?.id)
    }

    @Test
    fun `空列表返回 null`() {
        assertEquals(null, selectAudioTrack(emptyList(), AudioQualityOption.AUTO))
    }

    @Test
    fun `只要有轨道就一定选得出东西`() {
        val weird = listOf(track(30999, 1_000))
        assertTrue(selectAudioTrack(weird, AudioQualityOption.Q192) != null)
    }

    @Test
    fun `降级标记：请求 192K 实际拿到 64K 时应标记为降级`() {
        val stream = AudioStream(
            url = "https://example.com/a.m4s",
            backupUrls = emptyList(),
            qualityId = 30216,
            bandwidth = 64_000,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            deadlineEpochSeconds = 0L,
            requested = AudioQualityOption.Q192,
        )
        assertTrue(stream.downgraded)
        assertEquals("64K", stream.displayLabel)
    }

    @Test
    fun `自动档不算降级`() {
        val stream = AudioStream(
            url = "https://example.com/a.m4s",
            backupUrls = emptyList(),
            qualityId = 30216,
            bandwidth = 64_000,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            deadlineEpochSeconds = 0L,
            requested = AudioQualityOption.AUTO,
        )
        assertTrue(!stream.downgraded)
    }

    @Test
    fun `过期判断：deadline 只剩 3 分钟算即将过期`() {
        val now = 1_700_000_000L
        val soon = AudioStream(
            url = "u",
            backupUrls = emptyList(),
            qualityId = 30280,
            bandwidth = 192_000,
            mimeType = "",
            codecs = "",
            deadlineEpochSeconds = now + 180L,
            requested = AudioQualityOption.AUTO,
        )
        assertTrue(soon.isExpiringSoon(now))

        val fresh = soon.copy(deadlineEpochSeconds = now + 3600L)
        assertTrue(!fresh.isExpiringSoon(now))
    }
}
