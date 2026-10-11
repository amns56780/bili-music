package com.bilimusic.app.domain.model

/**
 * 登录后的 B 站账号信息（FR-1：设置页要显示头像、昵称、UID）。
 */
data class Account(
    val mid: Long,
    val name: String,
    val faceUrl: String,
    val isVip: Boolean = false,
) {
    val uidText: String get() = "UID: $mid"
}
