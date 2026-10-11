package com.bilimusic.app.domain.model

/**
 * FR-4 四种播放模式。
 * 映射关系（任务书 7.3）：
 * 顺序 = repeatMode OFF + shuffle off；列表循环 = REPEAT_MODE_ALL；
 * 单曲循环 = REPEAT_MODE_ONE；随机 = shuffleModeEnabled = true（固定 seed）。
 *
 * 默认模式是**列表循环**：整张歌单放完自动从头继续，不会听着听着就停了。
 */
enum class PlayMode(val displayName: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ALL("列表循环"),
    REPEAT_ONE("单曲循环"),
    SHUFFLE("随机播放"),
    ;

    companion object {
        /** 默认模式（新装 / 没存过偏好时用它） */
        val DEFAULT: PlayMode = REPEAT_ALL

        /** 解析持久化的枚举名；空值或脏数据都回退到 [DEFAULT] */
        fun fromNameOrRepeatAll(name: String?): PlayMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
