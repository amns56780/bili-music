package com.bilimusic.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * B 站接口统一响应外壳：{ code, message, ttl, data }。
 * 所有接口都返回这个结构，code == 0 才算成功。
 */
@Serializable
data class BiliResponse<T>(
    val code: Int = -1,
    val message: String = "",
    val ttl: Int = 1,
    val data: T? = null,
)
