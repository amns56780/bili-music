package com.bilimusic.app.domain.model

/**
 * FR-10 音质档位。
 *
 * 对照表（任务书 4.4）：
 * 30216 = 64Kbps，30232 = 132Kbps，30280 = 192Kbps，
 * 30250 = 杜比全景声，30251 = Hi-Res 无损。
 */
enum class AudioQualityOption(
    val displayName: String,
    val settingLabel: String,
    /** null 表示「自动」 */
    val qualityId: Int?,
) {
    AUTO("自动（推荐）", "自动", null),
    Q192("192K", "192K", 30280),
    Q132("132K", "132K", 30232),
    Q64("64K", "64K", 30216),
    HIRES("Hi-Res 无损（若可用）", "Hi-Res", 30251),
    DOLBY("杜比全景声（若可用）", "杜比", 30250),
    ;

    companion object {
        fun fromNameOrAuto(name: String?): AudioQualityOption =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** 一档音质在本次取流里的实际信息 */
data class AudioStream(
    val url: String,
    val backupUrls: List<String>,
    val qualityId: Int,
    val bandwidth: Long,
    val mimeType: String,
    val codecs: String,
    /** 过期时刻（epoch 秒），来自 URL 里的 deadline 参数 */
    val deadlineEpochSeconds: Long,
    /** 用户请求的档位（用于「不支持档位已自动降级」提示） */
    val requested: AudioQualityOption,
) {
    val displayLabel: String
        get() = when (qualityId) {
            30216 -> "64K"
            30232 -> "132K"
            30280 -> "192K"
            30250 -> "杜比全景声"
            30251 -> "Hi-Res 无损"
            else -> "未知音质($qualityId)"
        }

    /** 是否发生了降级（用户点了具体档位但拿到的不是那一档） */
    val downgraded: Boolean
        get() = requested.qualityId != null && requested.qualityId != qualityId

    /** 距离过期还有多少毫秒（负数表示已过期） */
    fun remainingMs(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Long =
        (deadlineEpochSeconds - nowEpochSeconds) * 1000L

    fun isExpiringSoon(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        remainingMs(nowEpochSeconds) < EXPIRY_SAFETY_MS

    companion object {
        /** 任务书：URL 只有 120 分钟有效期，留 5 分钟安全余量 */
        const val EXPIRY_SAFETY_MS = 5L * 60L * 1000L
    }
}

/**
 * 按用户档位从 dash.audio[] 里挑一档。
 * 规则（任务书 4.4）：匹配不到就按 bandwidth 从高到低降级挑最接近的一档；
 * 「自动」优先 192K → 132K → 64K，任何情况下都必须能播出来。
 */
fun selectAudioTrack(
    tracks: List<DashAudioTrack>,
    option: AudioQualityOption,
): DashAudioTrack? {
    if (tracks.isEmpty()) return null
    val sorted = tracks.sortedByDescending { it.bandwidth }
    option.qualityId?.let { wanted ->
        sorted.firstOrNull { it.id == wanted }?.let { return it }
    }
    // 自动档 / 指定档位不可用：走 192K → 132K → 64K 的降级链
    val autoChain = listOf(30280, 30232, 30216)
    autoChain.forEach { id ->
        sorted.firstOrNull { it.id == id }?.let { return it }
    }
    // 实在没有常见档位（比如只有杜比），给带宽最高的一档
    return sorted.firstOrNull()
}

/** 取流结果里一档音频（与 DTO 解耦，便于单测） */
data class DashAudioTrack(
    val id: Int,
    val baseUrl: String,
    val backupUrls: List<String>,
    val bandwidth: Long,
    val mimeType: String,
    val codecs: String,
)
