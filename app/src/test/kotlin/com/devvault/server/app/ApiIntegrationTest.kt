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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 需要一个可用的 MySQL，未设置 DEVVAULT_TEST_DB_URL 时整个测试类跳过。例如：
 * DEVVAULT_TEST_DB_URL=jdbc:mysql://127.0.0.1:3307/devvault_test DEVVAULT_TEST_DB_USER=devvault DEVVAULT_TEST_DB_PASSWORD=devvault
 */
@EnabledIfEnvironmentVariable(named = "DEVVAULT_TEST_DB_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    private fun key() = Crypto.base64(Crypto.randomBytes(32))
    private val kdf = KdfParams(n = 1 shl 14, r = 8, p = 1, salt = Crypto.base64(Crypto.randomBytes(16)))

    private fun <T> post(path: String, body: Any, type: Class<T>, token: String? = null): ResponseEntity<T> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            token?.let { setBearerAuth(it) }
        }
        return rest.exchange(path, HttpMethod.POST, HttpEntity(body, headers), type)
    }

    private fun <T> get(path: String, type: Class<T>, token: String? = null): ResponseEntity<T> {
        val headers = HttpHeaders()
        token?.let { headers.setBearerAuth(it) }
        return rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), type)
    }

    /** 无请求体的 POST（注销、修改密码之前的空调用） */
    private fun <T> postNoBody(path: String, type: Class<T>, token: String? = null): ResponseEntity<T> {
        val headers = HttpHeaders()
        token?.let { headers.setBearerAuth(it) }
        return rest.exchange(path, HttpMethod.POST, HttpEntity<Void>(headers), type)
    }

    @Test
    fun authAndSyncFlow() {
        val info = get("/api/server/info", ServerInfo::class.java).body!!
        assertEquals(API_VERSION, info.apiVersion)

        val username = "user_${System.nanoTime()}"
        val authKey = key()
        val reg = post(
            "/api/auth/register",
            RegisterRequest(username, "测试", null, kdf, authKey, "v1.wrapped-key"),
            LoginResponse::class.java,
        )
        assertEquals(HttpStatus.OK, reg.statusCode)

        // 账号不区分大小写，重复注册返回 409
        val dup = post(
            "/api/auth/register",
            RegisterRequest(username.uppercase(), "", null, kdf, authKey, "v1.x"),
            ErrorResponse::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, dup.statusCode)

        // prelogin：已存在账号返回真实参数；不存在的账号返回稳定的假 salt
        val pre = post("/api/auth/prelogin", PreloginRequest(username), PreloginResponse::class.java).body!!
        assertEquals(kdf.salt, pre.kdf.salt)
        val fake1 = post("/api/auth/prelogin", PreloginRequest("nobody_$username"), PreloginResponse::class.java).body!!
        val fake2 = post("/api/auth/prelogin", PreloginRequest("nobody_$username"), PreloginResponse::class.java).body!!
        assertEquals(fake1.kdf.salt, fake2.kdf.salt)

        val bad = post("/api/auth/login", LoginRequest(username, key()), ErrorResponse::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, bad.statusCode)
        assertEquals("账号或密码错误", bad.body!!.message)

        val login = post("/api/auth/login", LoginRequest(username, authKey), LoginResponse::class.java).body!!
        assertEquals("v1.wrapped-key", login.encryptedVaultKey)
        val token = login.token

        // 推送两条新记录
        val push1 = post(
            "/api/sync/push",
            PushRequest(
                listOf(
                    SyncChange("todo", "t1", 0, modifiedAt = 1, payload = "v1.aaa"),
                    SyncChange("entry", "e1", 0, modifiedAt = 1, payload = "v1.bbb"),
                ),
            ),
            PushResponse::class.java,
            token,
        ).body!!
        assertEquals(2, push1.accepted.size)
        assertTrue(push1.conflicts.isEmpty())
        val t1Rev = push1.accepted.first { it.id == "t1" }.revision

        // 过期的 baseRevision 视为冲突
        val stale = post(
            "/api/sync/push",
            PushRequest(listOf(SyncChange("todo", "t1", 0, modifiedAt = 2, payload = "v1.ccc"))),
            PushResponse::class.java,
            token,
        ).body!!
        assertEquals(1, stale.conflicts.size)
        assertEquals(t1Rev, stale.conflicts[0].revision)

        val del = post(
            "/api/sync/push",
            PushRequest(listOf(SyncChange("todo", "t1", t1Rev, deleted = true, modifiedAt = 3))),
            PushResponse::class.java,
            token,
        ).body!!
        assertEquals(1, del.accepted.size)

        // 分页拉取
        val page1 = get("/api/sync/pull?since=0&limit=1", PullResponse::class.java, token).body!!
        assertTrue(page1.hasMore)
        val page2 = get("/api/sync/pull?since=${page1.revision}&limit=10", PullResponse::class.java, token).body!!
        assertFalse(page2.hasMore)
        val all = page1.items + page2.items
        assertEquals(setOf("t1", "e1"), all.map { it.id }.toSet())
        val t1 = all.first { it.id == "t1" }
        assertTrue(t1.deleted)
        assertEquals(null, t1.payload)
        assertNotEquals(0, page2.revision)

        // 注销后令牌失效
        assertEquals(HttpStatus.NO_CONTENT, postNoBody("/api/auth/logout", Void::class.java, token).statusCode)
        val after = get("/api/sync/pull", ErrorResponse::class.java, token)
        assertEquals(HttpStatus.UNAUTHORIZED, after.statusCode)
        assertEquals("登录已过期，请重新登录", after.body!!.message)
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/sync/pull", ErrorResponse::class.java).statusCode)
    }

    @Test
    fun loginIsThrottled() {
        val username = "throttle_${System.nanoTime()}"
        post("/api/auth/register", RegisterRequest(username, "", null, kdf, key(), "v1.k"), LoginResponse::class.java)
        repeat(5) {
            assertEquals(
                HttpStatus.UNAUTHORIZED,
                post("/api/auth/login", LoginRequest(username, key()), ErrorResponse::class.java).statusCode,
            )
        }
        assertEquals(
            HttpStatus.TOO_MANY_REQUESTS,
            post("/api/auth/login", LoginRequest(username, key()), ErrorResponse::class.java).statusCode,
        )
    }

    @Test
    fun unknownRouteReturnsJsonError() {
        val res = get("/api/does-not-exist", ErrorResponse::class.java)
        assertEquals(HttpStatus.NOT_FOUND, res.statusCode)
        assertEquals("接口不存在", res.body!!.message)
    }

    @Test
    fun malformedBodyReturns400() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val res = rest.exchange("/api/auth/login", HttpMethod.POST, HttpEntity("{not json", headers), ErrorResponse::class.java)
        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode)
        assertEquals("请求格式不正确", res.body!!.message)
    }

    companion object {
        /** 把外部 MySQL 的连接信息注入测试上下文 */
        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("devvault.db.url") { System.getenv("DEVVAULT_TEST_DB_URL") ?: "" }
            registry.add("devvault.db.user") { System.getenv("DEVVAULT_TEST_DB_USER") ?: "devvault" }
            registry.add("devvault.db.password") { System.getenv("DEVVAULT_TEST_DB_PASSWORD") ?: "" }
            registry.add("devvault.db.pool-size") { "4" }
            registry.add("devvault.name") { "Test" }
            registry.add("devvault.allow-registration") { "true" }
        }
    }
}
