package com.devvault.server.app.web

import com.devvault.server.app.API_VERSION
import com.devvault.server.app.config.AppProperties
import com.devvault.server.core.ServerInfo
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ServerInfoController(private val props: AppProperties) {

    /** GET /api/server/info —— 客户端登录 / 注册前调用，用于判断是否兼容 */
    @GetMapping("/api/server/info")
    fun info(): ServerInfo = ServerInfo(
        name = props.name,
        version = implementationVersion(),
        apiVersion = API_VERSION,
        allowRegistration = props.allowRegistration,
    )

    /** GET / —— 与原先一致的服务自检入口 */
    @GetMapping("/")
    fun root(): Map<String, String> = mapOf("service" to "devvault", "api" to "/api/server/info")

    /** jar 启动时取 manifest 中的实现版本，源码运行（bootRun）时为 dev */
    private fun implementationVersion(): String =
        ServerInfoController::class.java.`package`?.implementationVersion ?: "dev"
}
