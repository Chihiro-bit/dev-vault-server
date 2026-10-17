package com.devvault.server.app.web

import com.devvault.server.service.AuthService
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(private val auth: AuthService) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(BearerAuthInterceptor(auth))
            .addPathPatterns(*PROTECTED_PATHS)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AuthUserArgumentResolver())
    }

    private companion object {
        /** 需要 Bearer 令牌的路径，与原先 `authenticate(AUTH_SESSION) { }` 包裹的范围一致 */
        val PROTECTED_PATHS = arrayOf(
            "/api/auth/me",
            "/api/auth/logout",
            "/api/auth/password",
            "/api/sync/**",
        )
    }
}
