package com.devvault.server.app

import com.devvault.server.core.ApiException
import com.devvault.server.core.ErrorResponse
import com.devvault.server.persistence.DatabaseFactory
import com.devvault.server.persistence.DbConfig
import com.devvault.server.service.AuthService
import com.devvault.server.service.SyncService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import java.time.Duration

data class AppConfig(
    val name: String,
    val allowRegistration: Boolean,
    val sessionDays: Long,
    val trustProxy: Boolean,
    val db: DbConfig,
)

fun Application.loadConfig(): AppConfig {
    val c = environment.config.config("devvault")
    return AppConfig(
        name = c.property("name").getString(),
        allowRegistration = c.property("allowRegistration").getString().toBoolean(),
        sessionDays = c.property("sessionDays").getString().toLong(),
        trustProxy = c.property("trustProxy").getString().toBoolean(),
        db = DbConfig(
            url = c.property("db.url").getString(),
            user = c.property("db.user").getString(),
            password = c.property("db.password").getString(),
            poolSize = c.property("db.poolSize").getString().toInt(),
        ),
    )
}

const val AUTH_SESSION = "session"

/** 由 application.conf 引用的入口模块 */
fun Application.module() {
    val config = loadConfig()
    val db = DatabaseFactory(config.db)
    monitor.subscribe(ApplicationStopped) { db.close() }

    val auth = AuthService(db, config.allowRegistration, Duration.ofDays(config.sessionDays))
    val sync = SyncService(db)
    configure(config)
    routes(config, auth, sync)
}

fun Application.configure(config: AppConfig) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header(HttpHeaders.CacheControl, "no-store")
    }
    if (config.trustProxy) install(XForwardedHeaders)
    install(CallLogging) {
        // 不记录请求体和令牌，只记录方法、路径与状态码
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorResponse(e.message)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求格式不正确")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("服务器内部错误"))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ErrorResponse("登录已过期，请重新登录"))
        }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ErrorResponse("接口不存在")) }
    }
}

fun Application.installAuth(auth: AuthService) {
    install(Authentication) {
        bearer(AUTH_SESSION) {
            authenticate { credential -> auth.authenticate(credential.token) }
        }
    }
}
