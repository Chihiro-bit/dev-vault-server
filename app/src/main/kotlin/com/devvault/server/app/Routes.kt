package com.devvault.server.app

import com.devvault.server.core.ApiException
import com.devvault.server.core.ChangePasswordRequest
import com.devvault.server.core.LoginRequest
import com.devvault.server.core.PreloginRequest
import com.devvault.server.core.PushRequest
import com.devvault.server.core.RegisterRequest
import com.devvault.server.core.ServerInfo
import com.devvault.server.service.AuthService
import com.devvault.server.service.AuthUser
import com.devvault.server.service.SyncService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** 接口版本：协议有不兼容变更时递增，客户端据此提示升级 */
const val API_VERSION = 1

private fun RoutingCall.authUser(): AuthUser = principal<AuthUser>() ?: throw ApiException.unauthorized()

fun Application.routes(config: AppConfig, auth: AuthService, sync: SyncService) {
    installAuth(auth)
    val version = AppConfig::class.java.`package`?.implementationVersion ?: "dev"

    routing {
        route("/api") {
            get("/server/info") {
                call.respond(ServerInfo(config.name, version, API_VERSION, config.allowRegistration))
            }

            route("/auth") {
                post("/prelogin") {
                    call.respond(auth.prelogin(call.receive<PreloginRequest>().username))
                }
                post("/register") {
                    call.respond(auth.register(call.receive<RegisterRequest>()))
                }
                post("/login") {
                    call.respond(auth.login(call.receive<LoginRequest>(), call.request.origin.remoteHost))
                }
                authenticate(AUTH_SESSION) {
                    get("/me") { call.respond(auth.me(call.authUser())) }
                    post("/logout") {
                        auth.logout(call.authUser())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/password") {
                        auth.changePassword(call.authUser(), call.receive<ChangePasswordRequest>())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }

            authenticate(AUTH_SESSION) {
                route("/sync") {
                    get("/pull") {
                        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        call.respond(sync.pull(call.authUser(), since, limit))
                    }
                    post("/push") {
                        call.respond(sync.push(call.authUser(), call.receive<PushRequest>()))
                    }
                }
            }
        }

        get("/") { call.respond(mapOf("service" to "devvault", "api" to "/api/server/info")) }
    }
}
