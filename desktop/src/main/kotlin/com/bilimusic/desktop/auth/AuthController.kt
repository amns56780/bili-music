package com.bilimusic.desktop.auth

import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliClient
import com.bilimusic.app.data.remote.apiCall
import com.bilimusic.app.domain.model.Account
import com.bilimusic.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 登录页的界面状态 */
data class AuthUiState(
    val phase: Phase = Phase.Checking,
    val account: Account? = null,
    /** 二维码内容（交给 ZXing 画出来） */
    val qrContent: String? = null,
    val qrStatus: String = "",
    val qrRemainingSec: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
) {
    enum class Phase { Checking, LoggedOut, LoggedIn }
}

/**
 * 登录状态机（FR-1）：扫码登录 + 粘贴 Cookie 两条路径。
 *
 * 与安卓端 AuthRepository 用的是同一套 shared 逻辑（CookieStore / BiliApi），
 * 所以判定规则一致：能拿到 `nav` 里 `isLogin=true` 就算登录成功。
 */
class AuthController(
    private val client: BiliClient,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    private companion object {
        const val TAG = "DesktopAuth"
        const val POLL_INTERVAL_MS = 2_000L
        /** 二维码有效期（B 站约 180 秒） */
        const val QR_TTL_SECONDS = 180
    }

    /** 启动时调用：有 Cookie 就拉一次账号信息，没有就进登录页 */
    fun bootstrap() {
        scope.launch {
            if (!client.cookieStore.hasSessData) {
                _state.value = AuthUiState(phase = AuthUiState.Phase.LoggedOut)
                return@launch
            }
            refreshAccount()
        }
    }

    private suspend fun refreshAccount() {
        when (val result = apiCall { client.api.nav() }) {
            is ApiResult.Success -> {
                val nav = result.data
                if (nav.isLogin) {
                    _state.value = _state.value.copy(
                        phase = AuthUiState.Phase.LoggedIn,
                        account = Account(
                            mid = nav.mid,
                            name = nav.uname,
                            faceUrl = nav.face,
                            isVip = nav.vipStatus == 1,
                        ),
                        error = null,
                    )
                } else {
                    // Cookie 失效：清掉，回到登录页
                    client.cookieStore.clear()
                    _state.value = AuthUiState(phase = AuthUiState.Phase.LoggedOut)
                }
            }

            is ApiResult.Error -> {
                AppLog.w(TAG, "获取账号信息失败：${result.failure.userMessage}")
                _state.value = _state.value.copy(
                    phase = AuthUiState.Phase.LoggedOut,
                    error = result.failure.userMessage,
                )
            }
        }
    }

    // ---------------- 扫码登录 ----------------

    fun startQrLogin() {
        pollJob?.cancel()
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null, qrContent = null, qrStatus = "正在获取二维码…")
            when (val result = apiCall { client.api.qrGenerate() }) {
                is ApiResult.Success -> {
                    val data = result.data
                    if (data.url.isBlank() || data.qrcodeKey.isBlank()) {
                        _state.value = _state.value.copy(busy = false, qrStatus = "", error = "二维码获取失败，请重试")
                        return@launch
                    }
                    _state.value = _state.value.copy(
                        busy = false,
                        qrContent = data.url,
                        qrStatus = "请用 B 站手机客户端扫码",
                        qrRemainingSec = QR_TTL_SECONDS,
                        error = null,
                    )
                    startPolling(data.qrcodeKey)
                }

                is ApiResult.Error -> _state.value = _state.value.copy(
                    busy = false,
                    qrStatus = "",
                    error = result.failure.userMessage,
                )
            }
        }
    }

    private fun startPolling(qrcodeKey: String) {
        pollJob = scope.launch {
            var elapsed = 0
            while (elapsed < QR_TTL_SECONDS) {
                delay(POLL_INTERVAL_MS)
                elapsed += (POLL_INTERVAL_MS / 1000).toInt()
                _state.value = _state.value.copy(qrRemainingSec = (QR_TTL_SECONDS - elapsed).coerceAtLeast(0))

                when (val result = apiCall { client.api.qrPoll(qrcodeKey) }) {
                    is ApiResult.Success -> {
                        val data = result.data
                        when (data.code) {
                            0 -> {
                                // 登录成功：Set-Cookie 已被 CookieJar 接住，这里再兜底解析一次 url
                                client.cookieStore.mergeFromLoginUrl(data.url)
                                _state.value = _state.value.copy(qrContent = null, qrStatus = "登录成功，正在读取账号…")
                                refreshAccount()
                                return@launch
                            }

                            86090 -> _state.value = _state.value.copy(qrStatus = "已扫码，请在手机上确认")
                            86101 -> _state.value = _state.value.copy(qrStatus = "请用 B 站手机客户端扫码")
                            86038 -> {
                                _state.value = _state.value.copy(
                                    qrContent = null,
                                    qrStatus = "",
                                    error = "二维码已失效，请刷新",
                                )
                                return@launch
                            }

                            else -> _state.value = _state.value.copy(
                                qrStatus = data.message.ifBlank { "扫码状态：${data.code}" },
                            )
                        }
                    }

                    is ApiResult.Error -> {
                        // 轮询失败不致命（网络抖动），继续轮询直到超时
                        AppLog.w(TAG, "轮询扫码状态失败：${result.failure.userMessage}")
                    }
                }
            }
            _state.value = _state.value.copy(qrContent = null, qrStatus = "", error = "二维码已过期，请刷新")
        }
    }

    // ---------------- 粘贴 Cookie ----------------

    /** 手动粘贴整段 Cookie（或只粘 SESSDATA 的值） */
    fun submitCookie(raw: String) {
        scope.launch {
            val parsed = client.cookieStore.parseRawCookieInput(raw)
            if (parsed.isEmpty()) {
                _state.value = _state.value.copy(error = "没解析出有效 Cookie，请检查粘贴内容")
                return@launch
            }
            // 整体替换，避免旧账号残留
            client.cookieStore.replaceAll(parsed)
            _state.value = _state.value.copy(busy = true, error = null)
            refreshAccount()
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun logout() {
        pollJob?.cancel()
        scope.launch {
            client.cookieStore.clear()
            _state.value = AuthUiState(phase = AuthUiState.Phase.LoggedOut)
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }
}
