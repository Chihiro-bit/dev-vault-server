package com.devvault.server.app.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 与原先 application.conf 中 `devvault { }` 一致，环境变量名保持不变，
 * 因此 docker-compose 与部署脚本无需改动。见 app/src/main/resources/application.yml。
 */
@ConfigurationProperties(prefix = "devvault")
data class AppProperties(
    /** 显示在客户端的服务器名称 */
    val name: String = "DevVault",
    /** 是否开放注册 */
    val allowRegistration: Boolean = true,
    /** 登录有效期（天） */
    val sessionDays: Long = 30,
    /** 部署在反向代理之后时为 true，从 X-Forwarded-For 获取真实 IP */
    val trustProxy: Boolean = false,
    val db: DbProperties = DbProperties(),
)

data class DbProperties(
    val url: String = "",
    val user: String = "devvault",
    val password: String = "",
    val poolSize: Int = 10,
)
