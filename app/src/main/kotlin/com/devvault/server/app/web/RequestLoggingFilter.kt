package com.devvault.server.app.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 等价于原先 Ktor 的 DefaultHeaders + CallLogging：
 * - 固定响应头（禁止 MIME 嗅探、不缓存）
 * - 只记录方法、路径与状态码，不记录请求体和令牌
 */
@Component
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val startedAt = System.currentTimeMillis()
        try {
            filterChain.doFilter(request, response)
        } finally {
            log.info("{} {} -> {} ({}ms)", request.method, request.requestURI, response.status, System.currentTimeMillis() - startedAt)
        }
    }
}
