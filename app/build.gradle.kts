plugins {
    application
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
    applicationName = "devvault-server"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":persistence"))
    implementation(project(":service"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.serialization.json)
    implementation(libs.logback)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

tasks.test {
    // 集成测试依赖外部数据库：设置了测试库时每次都真正执行，不使用缓存结果
    val testDb = System.getenv("DEVVAULT_TEST_DB_URL")
    inputs.property("testDb", testDb ?: "")
    if (testDb != null) outputs.upToDateWhen { false }
}
