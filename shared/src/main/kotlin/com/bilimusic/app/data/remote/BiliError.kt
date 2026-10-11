package com.bilimusic.app.data.remote

/**
 * 统一错误类型。UI 层只拿中文可读文案，不直接看 code。
 */
enum class FailureKind {
    /** 网络层失败：超时、断网、DNS、HTTP 5xx */
    NETWORK,

    /** 登录态问题：-101 未登录、-111 CSRF */
    AUTH,

    /** 风控：-352、-412 */
    RISK_CONTROL,

    /** 没有权限 / 签名错误 / 私密：-403 */
    PERMISSION,

    /** 内容不存在或失效：-404、62002、62004 */
    NOT_FOUND,

    /** 其它业务失败 */
    UNKNOWN,
}

/**
 * B 站错误码 → 中文可读提示（任务书 4.5）。
 */
object BiliErrorMapper {

    fun map(code: Int, serverMessage: String?): Failure {
        val fallback = serverMessage?.takeIf { it.isNotBlank() }
        return when (code) {
            -101 -> Failure("登录已过期，请重新登录", code, FailureKind.AUTH)
            -111 -> Failure("登录状态校验失败（CSRF），请重新登录", code, FailureKind.AUTH)
            -352 -> Failure("操作被 B 站风控拦截，请稍后再试", code, FailureKind.RISK_CONTROL)
            -403 -> Failure("没有访问权限：可能是私密内容，或接口签名已变更", code, FailureKind.PERMISSION)
            -404 -> Failure("内容不存在或已被删除", code, FailureKind.NOT_FOUND)
            -412 -> Failure("请求被 B 站拦截（-412），请降低操作频率后重试", code, FailureKind.RISK_CONTROL)
            62002 -> Failure("稿件不可见", code, FailureKind.NOT_FOUND)
            62004 -> Failure("稿件审核中", code, FailureKind.NOT_FOUND)
            else -> Failure(fallback ?: "请求失败（错误码 $code）", code, FailureKind.UNKNOWN)
        }
    }

    fun network(throwable: Throwable): Failure {
        val text = when (throwable) {
            is java.net.SocketTimeoutException -> "网络超时，请检查网络后重试"
            is java.net.UnknownHostException -> "无法连接网络，请检查网络设置"
            is java.net.ConnectException -> "连接服务器失败，请稍后重试"
            is javax.net.ssl.SSLException -> "安全连接失败，请检查网络环境"
            else -> "网络异常：${throwable.message ?: throwable.javaClass.simpleName}"
        }
        return Failure(text, code = null, kind = FailureKind.NETWORK)
    }

    /** -352 / -412 明确要求「不要循环打」 */
    fun isRetryable(kind: FailureKind): Boolean = when (kind) {
        FailureKind.RISK_CONTROL -> false
        FailureKind.AUTH -> false
        FailureKind.PERMISSION -> false
        FailureKind.NOT_FOUND -> false
        FailureKind.UNKNOWN -> true
        FailureKind.NETWORK -> true
    }
}

/** 统一的失败载体 */
data class Failure(
    val userMessage: String,
    val code: Int?,
    val kind: FailureKind,
)

/** 统一的调用结果 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    data class Error(val failure: Failure) : ApiResult<Nothing>

    val isSuccess: Boolean get() = this is Success<*>

    fun getOrNull(): T? = (this as? Success)?.data
}
