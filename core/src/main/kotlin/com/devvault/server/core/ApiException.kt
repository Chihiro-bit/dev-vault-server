package com.devvault.server.core

/** 业务错误：HTTP 层将其转换为对应状态码与 `{ "message": ... }` 响应 */
class ApiException(val status: Int, override val message: String) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String) = ApiException(400, message)
        fun unauthorized(message: String = "登录已过期，请重新登录") = ApiException(401, message)
        fun forbidden(message: String) = ApiException(403, message)
        fun conflict(message: String) = ApiException(409, message)
        fun tooManyRequests(message: String) = ApiException(429, message)
    }
}

/** 校验失败时抛出 400 */
inline fun require400(condition: Boolean, message: () -> String) {
    if (!condition) throw ApiException.badRequest(message())
}
