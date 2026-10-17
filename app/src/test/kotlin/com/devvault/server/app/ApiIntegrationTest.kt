package com.devvault.server.app

import com.devvault.server.core.Crypto
import com.devvault.server.core.ErrorResponse
import com.devvault.server.core.KdfParams
import com.devvault.server.core.LoginRequest
import com.devvault.server.core.LoginResponse
import com.devvault.server.core.PreloginRequest
import com.devvault.server.core.PreloginResponse
import com.devvault.server.core.PullResponse
import com.devvault.server.core.PushRequest
import com.devvault.server.core.PushResponse
import com.devvault.server.core.RegisterRequest
import com.devvault.server.core.ServerInfo
import com.devvault.server.core.SyncChange
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/**
 * 需要一个可用的 MySQL，未设置 DEVVAULT_TEST_DB_URL 时跳过。例如：
 * DEVVAULT_TEST_DB_URL=jdbc:mysql://127.0.0.1:3307/devvault_test DEVVAULT_TEST_DB_USER=devvault DEVVAULT_TEST_DB_PASSWORD=devvault
 */
class ApiIntegrationTest {
    private val dbUrl: String? = System.getenv("DEVVAULT_TEST_DB_URL")

    private fun key() = Crypto.base64(Crypto.randomBytes(32))
    private val kdf = KdfParams(n = 1 shl 14, r = 8, p = 1, salt = Crypto.base64(Crypto.randomBytes(16)))

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) {
        val url = dbUrl ?: return println("跳过集成测试：未设置 DEVVAULT_TEST_DB_URL")
        testApplication {
            environment {
                config = MapApplicationConfig(
                    "devvault.name" to "Test",
                    "devvault.allowRegistration" to "true",
                    "devvault.sessionDays" to "30",
                    "devvault.trustProxy" to "false",
                    "devvault.db.url" to url,
                    "devvault.db.user" to (System.getenv("DEVVAULT_TEST_DB_USER") ?: "devvault"),
                    "devvault.db.password" to (System.getenv("DEVVAULT_TEST_DB_PASSWORD") ?: ""),
                    "devvault.db.poolSize" to "4",
                )
            }
            application { module() }
            val client = createClient { install(ClientContentNegotiation) { json() } }
            block(client)
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: Any, token: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            token?.let { bearerAuth(it) }
            setBody(body)
        }

    @Test
    fun authAndSyncFlow() = api { client ->
        val info = client.get("/api/server/info").body<ServerInfo>()
        assertEquals(API_VERSION, info.apiVersion)

        val username = "user_${System.nanoTime()}"
        val authKey = key()
        val reg = client.postJson(
            "/api/auth/register",
            RegisterRequest(username, "测试", null, kdf, authKey, "v1.wrapped-key"),
        )
        assertEquals(HttpStatusCode.OK, reg.status)
        val dup = client.postJson(
            "/api/auth/register",
            RegisterRequest(username.uppercase(), "", null, kdf, authKey, "v1.x"),
        )
        assertEquals(HttpStatusCode.Conflict, dup.status)

        // prelogin：已存在账号返回真实参数；不存在的账号返回稳定的假 salt
        val pre = client.postJson("/api/auth/prelogin", PreloginRequest(username)).body<PreloginResponse>()
        assertEquals(kdf.salt, pre.kdf.salt)
        val fake1 = client.postJson("/api/auth/prelogin", PreloginRequest("nobody_$username")).body<PreloginResponse>()
        val fake2 = client.postJson("/api/auth/prelogin", PreloginRequest("nobody_$username")).body<PreloginResponse>()
        assertEquals(fake1.kdf.salt, fake2.kdf.salt)

        val bad = client.postJson("/api/auth/login", LoginRequest(username, key()))
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("账号或密码错误", bad.body<ErrorResponse>().message)

        val login = client.postJson("/api/auth/login", LoginRequest(username, authKey)).body<LoginResponse>()
        assertEquals("v1.wrapped-key", login.encryptedVaultKey)
        val token = login.token

        // 推送两条新记录
        val push1 = client.postJson(
            "/api/sync/push",
            PushRequest(
                listOf(
                    SyncChange("todo", "t1", 0, modifiedAt = 1, payload = "v1.aaa"),
                    SyncChange("entry", "e1", 0, modifiedAt = 1, payload = "v1.bbb"),
                ),
            ),
            token,
        ).body<PushResponse>()
        assertEquals(2, push1.accepted.size)
        assertTrue(push1.conflicts.isEmpty())
        val t1Rev = push1.accepted.first { it.id == "t1" }.revision

        // 过期的 baseRevision 视为冲突
        val stale = client.postJson(
            "/api/sync/push",
            PushRequest(listOf(SyncChange("todo", "t1", 0, modifiedAt = 2, payload = "v1.ccc"))),
            token,
        ).body<PushResponse>()
        assertEquals(1, stale.conflicts.size)
        assertEquals(t1Rev, stale.conflicts[0].revision)

        val del = client.postJson(
            "/api/sync/push",
            PushRequest(listOf(SyncChange("todo", "t1", t1Rev, deleted = true, modifiedAt = 3))),
            token,
        ).body<PushResponse>()
        assertEquals(1, del.accepted.size)

        // 分页拉取
        val page1 = client.get("/api/sync/pull?since=0&limit=1") { bearerAuth(token) }.body<PullResponse>()
        assertTrue(page1.hasMore)
        val page2 = client.get("/api/sync/pull?since=${page1.revision}&limit=10") { bearerAuth(token) }.body<PullResponse>()
        assertFalse(page2.hasMore)
        val all = page1.items + page2.items
        assertEquals(setOf("t1", "e1"), all.map { it.id }.toSet())
        val t1 = all.first { it.id == "t1" }
        assertTrue(t1.deleted)
        assertEquals(null, t1.payload)
        assertNotEquals(0, page2.revision)

        // 注销后令牌失效
        assertEquals(HttpStatusCode.NoContent, client.post("/api/auth/logout") { bearerAuth(token) }.status)
        val after = client.get("/api/sync/pull") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, after.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sync/pull").status)
    }

    @Test
    fun loginIsThrottled() = api { client ->
        val username = "throttle_${System.nanoTime()}"
        client.postJson("/api/auth/register", RegisterRequest(username, "", null, kdf, key(), "v1.k"))
        repeat(5) {
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/auth/login", LoginRequest(username, key())).status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/api/auth/login", LoginRequest(username, key())).status)
    }
}
