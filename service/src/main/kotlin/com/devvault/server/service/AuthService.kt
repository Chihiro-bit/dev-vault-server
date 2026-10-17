package com.devvault.server.service

import com.devvault.server.core.ApiException
import com.devvault.server.core.ChangePasswordRequest
import com.devvault.server.core.Crypto
import com.devvault.server.core.KdfParams
import com.devvault.server.core.LoginRequest
import com.devvault.server.core.LoginResponse
import com.devvault.server.core.PreloginResponse
import com.devvault.server.core.RegisterRequest
import com.devvault.server.core.UserDto
import com.devvault.server.core.Validation
import com.devvault.server.persistence.DatabaseFactory
import com.devvault.server.persistence.MetaRepository
import com.devvault.server.persistence.SessionRepository
import com.devvault.server.persistence.UserRecord
import com.devvault.server.persistence.UserRepository
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 已认证的请求身份 */
data class AuthUser(val userId: Long, val sessionId: Long, val tokenHash: String)

class AuthService(
    private val db: DatabaseFactory,
    private val allowRegistration: Boolean,
    sessionTtl: Duration,
    private val throttle: LoginThrottle = LoginThrottle(),
) {
    private val ttl = sessionTtl

    /** 用于为不存在的账号生成稳定的假 salt，避免通过 prelogin 探测账号是否存在 */
    @Volatile
    private var pepper: ByteArray? = null

    private fun pepper(): ByteArray = pepper ?: db.tx {
        MetaRepository.getOrPut("prelogin.pepper") { Crypto.base64(Crypto.randomBytes(32)) }
    }.let { Crypto.decodeBase64(it)!! }.also { pepper = it }

    private val dummyHash by lazy { Crypto.hashAuthKey(Crypto.base64(Crypto.randomBytes(32))) }

    private fun now(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    private fun UserRecord.dto() = UserDto(id = id.toString(), name = displayName, username = username, email = email)

    fun prelogin(rawUsername: String): PreloginResponse {
        val username = Validation.normalizeUsername(rawUsername)
        val user = db.tx { UserRepository.findByUsername(username) }
        if (user != null) return PreloginResponse(user.kdf)
        val fakeSalt = Crypto.hmacSha256(pepper(), "prelogin:$username").copyOf(16)
        return PreloginResponse(DEFAULT_KDF.copy(salt = Crypto.base64(fakeSalt)))
    }

    fun register(req: RegisterRequest): LoginResponse {
        if (!allowRegistration) throw ApiException.forbidden("该服务器未开放注册，请联系管理员")
        val username = Validation.normalizeUsername(req.username)
        val name = Validation.displayName(req.name, username)
        val email = Validation.email(req.email)
        val kdf = Validation.kdf(req.kdf)
        val authHash = Crypto.hashAuthKey(Validation.authKey(req.authKey))
        val vaultKey = Validation.encryptedKey(req.encryptedVaultKey)

        val token = Crypto.randomToken()
        val user = try {
            db.tx {
                if (UserRepository.findByUsername(username) != null) throw ApiException.conflict("账号已被注册")
                val id = UserRepository.create(username, name, email, authHash, kdf, vaultKey)
                SessionRepository.create(id, Crypto.sha256Hex(token), Validation.device(req.device), now().plus(ttl))
                UserRepository.findById(id)!!
            }
        } catch (e: ExposedSQLException) {
            // 并发注册同名账号时由唯一索引兜底
            if (e.sqlState?.startsWith("23") == true) throw ApiException.conflict("账号已被注册")
            throw e
        }
        return LoginResponse(token, user.dto(), user.kdf, user.vaultKey)
    }

    fun login(req: LoginRequest, ip: String): LoginResponse {
        val username = Validation.normalizeUsername(req.username)
        throttle.check(username, ip)
        val authKey = Validation.authKey(req.authKey)
        val user = db.tx { UserRepository.findByUsername(username) }
        // 账号不存在时同样做一次哈希比对，避免通过响应耗时判断账号是否存在
        val valid = Crypto.verifyAuthKey(authKey, user?.authHash ?: dummyHash)
        if (user == null || !valid) {
            throttle.fail(username, ip)
            throw ApiException.unauthorized("账号或密码错误")
        }
        throttle.succeed(username, ip)
        val token = Crypto.randomToken()
        db.tx {
            SessionRepository.purgeExpired()
            SessionRepository.create(user.id, Crypto.sha256Hex(token), Validation.device(req.device), now().plus(ttl))
        }
        return LoginResponse(token, user.dto(), user.kdf, user.vaultKey)
    }

    /** 校验 Bearer 令牌；有效期为滑动窗口，每小时最多续期一次以减少写库 */
    fun authenticate(token: String): AuthUser? {
        if (token.isBlank() || token.length > 128) return null
        val hash = Crypto.sha256Hex(token)
        return db.tx {
            val s = SessionRepository.findByTokenHash(hash) ?: return@tx null
            val now = now()
            if (s.expiresAt.isBefore(now)) {
                SessionRepository.deleteByTokenHash(hash)
                return@tx null
            }
            if (Duration.between(s.lastUsedAt, now) > Duration.ofHours(1)) SessionRepository.touch(s.id, now.plus(ttl))
            AuthUser(s.userId, s.id, hash)
        }
    }

    fun me(auth: AuthUser): UserDto =
        db.tx { UserRepository.findById(auth.userId) }?.dto() ?: throw ApiException.unauthorized()

    fun logout(auth: AuthUser) = db.tx { SessionRepository.deleteByTokenHash(auth.tokenHash) }

    /**
     * 修改主密码：客户端用新密码重新包裹同一个数据密钥，已同步的数据无需重新加密。
     * 成功后其他设备上的登录全部失效。
     */
    fun changePassword(auth: AuthUser, req: ChangePasswordRequest) {
        val current = Validation.authKey(req.currentAuthKey)
        val kdf = Validation.kdf(req.kdf)
        val authHash = Crypto.hashAuthKey(Validation.authKey(req.authKey))
        val vaultKey = Validation.encryptedKey(req.encryptedVaultKey)
        db.tx {
            val user = UserRepository.findById(auth.userId) ?: throw ApiException.unauthorized()
            if (!Crypto.verifyAuthKey(current, user.authHash)) throw ApiException.forbidden("当前密码不正确")
            UserRepository.updateCredentials(user.id, authHash, kdf, vaultKey)
            SessionRepository.deleteOthers(user.id, auth.sessionId)
        }
    }

    companion object {
        /** 新用户的默认 scrypt 参数（N=2^17 约占用 128MB 内存），实际参数由客户端注册时提交 */
        val DEFAULT_KDF = KdfParams(type = "scrypt", n = 1 shl 17, r = 8, p = 1, salt = "")
    }
}
