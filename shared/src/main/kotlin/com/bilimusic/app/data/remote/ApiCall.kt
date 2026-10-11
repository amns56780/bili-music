package com.bilimusic.app.data.remote

import com.bilimusic.app.data.remote.dto.BiliResponse
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * 统一的接口调用包装（任务书 4.6）：
 * - 每个请求失败至少重试 1 次，指数退避，最多 3 次
 * - 但 -352 / -412 风控、-101 登录失效、-404 失效视频 **不重试**
 * - 所有失败都走 [BiliErrorMapper] 转成中文提示
 */
suspend fun <T> apiCall(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 400L,
    block: suspend () -> BiliResponse<T>,
): ApiResult<T> {
    var lastFailure: Failure? = null
    for (attempt in 1..maxAttempts) {
        try {
            val response = block()
            if (response.code == 0) {
                val data = response.data
                return if (data == null) {
                    ApiResult.Error(Failure("接口返回成功但没有数据", 0, FailureKind.UNKNOWN))
                } else {
                    ApiResult.Success(data)
                }
            }
            val failure = BiliErrorMapper.map(response.code, response.message)
            if (!BiliErrorMapper.isRetryable(failure.kind) || attempt == maxAttempts) {
                return ApiResult.Error(failure)
            }
            lastFailure = failure
        } catch (io: IOException) {
            lastFailure = BiliErrorMapper.network(io)
            if (attempt == maxAttempts) return ApiResult.Error(lastFailure)
        } catch (t: Throwable) {
            // 序列化异常等非网络异常：不重试，直接给可读提示
            return ApiResult.Error(Failure("数据处理失败：${t.message ?: t.javaClass.simpleName}", null, FailureKind.UNKNOWN))
        }
        delay(baseDelayMs * (1L shl (attempt - 1)))
    }
    return ApiResult.Error(lastFailure ?: Failure("请求失败", null, FailureKind.UNKNOWN))
}
