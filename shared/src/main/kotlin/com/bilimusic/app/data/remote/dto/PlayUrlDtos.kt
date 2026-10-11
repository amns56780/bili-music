package com.bilimusic.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 取音频流（/x/player/wbi/playurl 或 /x/player/playurl） */
@Serializable
data class PlayUrlData(
    /** DASH：音频流都在这里 */
    val dash: DashDto? = null,
    /** 兼容旧接口的 durl（有 backup_url 数组） */
    val durl: List<DurlDto>? = null,
    val quality: Int = 0,
    val timelength: Long = 0L,
)

@Serializable
data class DashDto(
    val duration: Int = 0,
    val audio: List<DashAudioDto>? = null,
)

@Serializable
data class DashAudioDto(
    val id: Int = 0,
    @SerialName("baseUrl")
    val baseUrlCamel: String = "",
    @SerialName("base_url")
    val baseUrlSnake: String = "",
    @SerialName("backupUrl")
    val backupUrlCamel: List<String>? = null,
    @SerialName("backup_url")
    val backupUrlSnake: List<String>? = null,
    val bandwidth: Long = 0L,
    val mimeType: String = "",
    val codecs: String = "",
) {
    /** 服务端 camelCase / snake_case 两种字段名都要能接住 */
    val baseUrl: String get() = baseUrlCamel.ifBlank { baseUrlSnake }

    val backupUrls: List<String>
        get() = (backupUrlCamel ?: backupUrlSnake).orEmpty().filter { it.isNotBlank() }
}

@Serializable
data class DurlDto(
    val order: Int = 1,
    val length: Long = 0L,
    val size: Long = 0L,
    val url: String = "",
    @SerialName("backup_url")
    val backupUrl: List<String>? = null,
)
