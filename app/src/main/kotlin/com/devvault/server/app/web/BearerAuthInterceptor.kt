package com.devvault.server.app.web

import com.devvault.server.core.ApiException
import com.devvault.server.service.AuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 等价于原先 Ktor 的 `install(Authentication) { bearer(AUTH_SESSION) { ... } }`：
 * 校验 `Authorization: Bearer <token>`，失败返回 401，成功把身份放进 request 属性。
 */
class BearerAuthInterceptor(private val auth: AuthService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith(PREFIX, ignoreCase = true)) throw ApiException.unauthorized()
        val token = header.substring(PREFIX.length).trim()
        val user = auth.authenticate(token) ?: throw ApiException.unauthorized()
        request.setAttribute(AUTH_USER_ATTRIBUTE, user)
        return true
    }

    private companion object {
        const val PREFIX = "Bearer "
    }
}
