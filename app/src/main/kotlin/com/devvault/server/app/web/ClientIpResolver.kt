package com.devvault.server.app.web

import com.devvault.server.app.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * 取客户端 IP 用于登录限流。trust-proxy 为 true 时信任反向代理写入的
 * X-Forwarded-For（取第一段，即真实客户端地址），否则只用连接地址。
 */
@Component
class ClientIpResolver(private val props: AppProperties) {

    fun of(request: HttpServletRequest): String {
        if (!props.trustProxy) return request.remoteAddr
        val forwarded = request.getHeader("X-Forwarded-For") ?: return request.remoteAddr
        return forwarded.split(',').firstOrNull()?.trim().orEmpty().ifEmpty { request.remoteAddr }
    }
}
