package com.bilimusic.app.data.player

import com.bilimusic.app.domain.model.AudioQualityOption

/**
 * 提供「当前音质档位」。
 *
 * 安卓端从 DataStore 读（PlaybackPrefs），桌面端从本地设置文件读；
 * 取流逻辑本身不关心存在哪，只关心取到什么。
 */
fun interface AudioQualityProvider {
    suspend fun current(): AudioQualityOption
}
