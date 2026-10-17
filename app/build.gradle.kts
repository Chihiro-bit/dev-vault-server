plugins {
    application
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

application {
    mainClass.set("com.devvault.server.app.DevVaultApplicationKt")
    applicationName = "devvault-server"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":persistence"))
    implementation(project(":service"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.logback)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
}

// 产出固定文件名，便于 Dockerfile 与部署脚本引用
tasks.bootJar {
    archiveFileName.set("devvault-server.jar")
}

tasks.test {
    // 集成测试依赖外部数据库：设置了测试库时每次都真正执行，不使用缓存结果
    val testDb = System.getenv("DEVVAULT_TEST_DB_URL")
    inputs.property("testDb", testDb ?: "")
    if (testDb != null) outputs.upToDateWhen { false }
}
