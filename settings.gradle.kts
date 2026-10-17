rootProject.name = "devvault-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// core：接口模型、错误、加密工具
// persistence：MySQL 连接、Flyway 迁移、表结构与仓储
// service：认证与同步业务逻辑
// app：Spring Boot HTTP 服务（可执行入口）
include("core", "persistence", "service", "app")
