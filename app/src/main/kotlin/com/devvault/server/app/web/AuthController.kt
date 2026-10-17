package com.devvault.server.app.web

import com.devvault.server.core.ChangePasswordRequest
import com.devvault.server.core.LoginRequest
import com.devvault.server.core.LoginResponse
import com.devvault.server.core.PreloginRequest
import com.devvault.server.core.PreloginResponse
import com.devvault.server.core.RegisterRequest
import com.devvault.server.core.UserDto
import com.devvault.server.service.AuthService
import com.devvault.server.service.AuthUser
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val auth: AuthService,
    private val clientIp: ClientIpResolver,
) {

    /** 返回该账号注册时使用的 KDF 参数；账号不存在时返回稳定的假 salt */
    @PostMapping("/prelogin")
    fun prelogin(@RequestBody req: PreloginRequest): PreloginResponse = auth.prelogin(req.username)

    @PostMapping("/register")
    fun register(@RequestBody req: RegisterRequest): LoginResponse = auth.register(req)

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest, request: HttpServletRequest): LoginResponse =
        auth.login(req, clientIp.of(request))

    @GetMapping("/me")
    fun me(@AuthPrincipal user: AuthUser): UserDto = auth.me(user)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthPrincipal user: AuthUser) {
        auth.logout(user)
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@AuthPrincipal user: AuthUser, @RequestBody req: ChangePasswordRequest) {
        auth.changePassword(user, req)
    }
}
