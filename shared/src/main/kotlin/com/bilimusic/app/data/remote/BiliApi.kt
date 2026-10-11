package com.bilimusic.app.data.remote

import com.bilimusic.app.data.remote.dto.BiliResponse
import com.bilimusic.app.data.remote.dto.FavFolderListData
import com.bilimusic.app.data.remote.dto.FavResourceListData
import com.bilimusic.app.data.remote.dto.NavData
import com.bilimusic.app.data.remote.dto.PlayUrlData
import com.bilimusic.app.data.remote.dto.QrGenerateData
import com.bilimusic.app.data.remote.dto.QrPollData
import com.bilimusic.app.data.remote.dto.SeasonArchivesData
import com.bilimusic.app.data.remote.dto.SpaceArcSearchData
import com.bilimusic.app.data.remote.dto.ToViewData
import com.bilimusic.app.data.remote.dto.ViewData
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * B 站接口唯一出口（任务书 4.3）。
 * 所有接口调用都必须集中在这里，方便日后 B 站改接口时统一适配。
 *
 * 需要 WBI 签名的接口统一用 @QueryMap 接收「业务参数 + wts + w_rid」，
 * 参数由 WbiSigner.sign() 产出，避免每个接口都要手写一遍签名参数。
 */
interface BiliApi {

    // ---------- FR-1 登录 ----------

    /** 扫码登录：申请二维码（新版，优先） */
    @GET("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
    suspend fun qrGenerate(): BiliResponse<QrGenerateData>

    /** 扫码登录：轮询结果（新版，优先）。成功后 Set-Cookie 里带全部登录 Cookie */
    @GET("https://passport.bilibili.com/x/passport-login/web/qrcode/poll")
    suspend fun qrPoll(@Query("qrcode_key") qrcodeKey: String): BiliResponse<QrPollData>

    /** 账号信息：同时用于登录态校验与 WBI key 获取（wbi_img） */
    @GET("x/web-interface/nav")
    suspend fun nav(): BiliResponse<NavData>

    // ---------- FR-2 歌单导入 ----------

    /** 收藏夹列表（Cookie + WBI）。参数：up_mid、wts、w_rid */
    @GET("x/v3/fav/folder/created/list-all")
    suspend fun favFolderList(@QueryMap params: Map<String, String>): BiliResponse<FavFolderListData>

    /** 收藏夹内容（Cookie + WBI）。参数：media_id、pn、ps=20、platform=web、wts、w_rid */
    @GET("x/v3/fav/resource/list")
    suspend fun favResourceList(@QueryMap params: Map<String, String>): BiliResponse<FavResourceListData>

    /** 稍后再看（Cookie，无需 WBI） */
    @GET("x/v2/history/toview")
    suspend fun toView(): BiliResponse<ToViewData>

    /** UP 主投稿（Cookie + WBI，风控最严）。参数：mid、pn、ps=30、order=pubdate、wts、w_rid */
    @GET("x/space/wbi/arc/search")
    suspend fun spaceArcSearch(@QueryMap params: Map<String, String>): BiliResponse<SpaceArcSearchData>

    /** 视频详情：拿 cid / 时长 / 封面 / UP主 / pages / ugc_season。参数：bvid 或 aid */
    @GET("x/web-interface/view")
    suspend fun videoView(@QueryMap params: Map<String, String>): BiliResponse<ViewData>

    /** 合集内容（Cookie 可选）。参数：mid、season_id、page_num、page_size、sort_reverse */
    @GET("x/polymer/web-space/seasons_archives_list")
    suspend fun seasonArchives(@QueryMap params: Map<String, String>): BiliResponse<SeasonArchivesData>

    // ---------- FR-4 / FR-10 取音频流 ----------

    /**
     * 取音频流（推荐，需 WBI）。
     * 参数：bvid、cid、fnval=4048、fnver=0、fourk=1、qn=0、wts、w_rid
     * 返回 data.dash.audio[]，每项带 baseUrl / backupUrl / bandwidth / mimeType。
     */
    @GET("x/player/wbi/playurl")
    suspend fun playUrl(@QueryMap params: Map<String, String>): BiliResponse<PlayUrlData>

    /** 取音频流（备选，不需要 WBI） */
    @GET("x/player/playurl")
    suspend fun playUrlLegacy(@QueryMap params: Map<String, String>): BiliResponse<PlayUrlData>
}
