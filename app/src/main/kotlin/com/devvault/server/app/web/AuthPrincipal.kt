package com.devvault.server.app.web

import com.devvault.server.core.ApiException
import com.devvault.server.service.AuthUser
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** 需要登录的接口用 `@AuthPrincipal user: AuthUser` 直接拿到当前身份 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class AuthPrincipal

/** AuthUser 在 request 中的属性名，由 BearerAuthInterceptor 写入 */
const val AUTH_USER_ATTRIBUTE = "devvault.authUser"

class AuthUserArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(AuthPrincipal::class.java) &&
            AuthUser::class.java.isAssignableFrom(parameter.parameterType)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any =
        webRequest.getAttribute(AUTH_USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? AuthUser
            ?: throw ApiException.unauthorized()
}
