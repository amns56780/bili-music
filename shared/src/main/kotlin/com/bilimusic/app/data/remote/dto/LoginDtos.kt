package com.bilimusic.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 新版扫码登录：申请二维码 */
@Serializable
data class QrGenerateData(
    /** 二维码内容（把它画成二维码给用户扫） */
    val url: String = "",
    @SerialName("qrcode_key")
    val qrcodeKey: String = "",
)

/** 新版扫码登录：轮询结果 */
@Serializable
data class QrPollData(
    /** 登录成功时，这个 url 的 query 里就带着 SESSDATA / bili_jct / DedeUserID 等 */
    val url: String = "",
    @SerialName("refresh_token")
    val refreshToken: String = "",
    val timestamp: Long = 0L,
    /** 0 成功 / 86101 未扫码 / 86090 已扫码未确认 / 86038 已失效 */
    val code: Int = -1,
    val message: String = "",
)

/** 账号信息（/x/web-interface/nav） */
@Serializable
data class NavData(
    @SerialName("isLogin")
    val isLogin: Boolean = false,
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    @SerialName("vipStatus")
    val vipStatus: Int = 0,
    @SerialName("wbi_img")
    val wbiImg: WbiImg? = null,
)

/** WBI 签名用的两个 key 图地址 */
@Serializable
data class WbiImg(
    @SerialName("img_url")
    val imgUrl: String = "",
    @SerialName("sub_url")
    val subUrl: String = "",
)
