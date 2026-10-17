package com.devvault.server.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val b64Url = Base64.getUrlEncoder().withoutPadding()

    private const val PBKDF2_ALG = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 100_000

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** URL 安全的随机令牌 */
    fun randomToken(bytes: Int = 32): String = b64Url.encodeToString(randomBytes(bytes))

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun hmacSha256(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray())
        }

    fun base64(bytes: ByteArray): String = b64.encodeToString(bytes)

    /** 严格解码 base64，非法输入返回 null */
    fun decodeBase64(s: String): ByteArray? =
        try {
            Base64.getDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * 对客户端提交的 authKey 再做一次 PBKDF2 后保存，数据库泄露时也无法直接用于登录。
     * 格式：pbkdf2$<迭代次数>$<salt>$<hash>
     */
    fun hashAuthKey(authKey: String): String {
        val salt = randomBytes(16)
        val hash = pbkdf2(authKey, salt, PBKDF2_ITERATIONS)
        return listOf("pbkdf2", PBKDF2_ITERATIONS.toString(), base64(salt), base64(hash)).joinToString("$")
    }

    fun verifyAuthKey(authKey: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = decodeBase64(parts[2]) ?: return false
        val expected = decodeBase64(parts[3]) ?: return false
        return MessageDigest.isEqual(pbkdf2(authKey, salt, iterations), expected)
    }

    private fun pbkdf2(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
