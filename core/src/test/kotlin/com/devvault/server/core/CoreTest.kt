package com.devvault.server.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    private val key32 = Crypto.base64(ByteArray(32) { it.toByte() })

    @Test
    fun authKeyHashRoundTrip() {
        val stored = Crypto.hashAuthKey(key32)
        assertTrue(Crypto.verifyAuthKey(key32, stored))
        assertFalse(Crypto.verifyAuthKey(Crypto.base64(ByteArray(32)), stored))
        assertFalse(Crypto.verifyAuthKey(key32, "garbage"))
    }

    @Test
    fun usernameIsCaseInsensitive() {
        assertEquals("zhang.san", Validation.normalizeUsername("  Zhang.San "))
        assertFailsWith<ApiException> { Validation.normalizeUsername("ab") }
        assertFailsWith<ApiException> { Validation.normalizeUsername("张三abc") }
    }

    @Test
    fun kdfBounds() {
        val salt = Crypto.base64(ByteArray(16))
        Validation.kdf(KdfParams(n = 1 shl 17, r = 8, p = 1, salt = salt))
        assertFailsWith<ApiException> { Validation.kdf(KdfParams(n = 1000, r = 8, p = 1, salt = salt)) }
        assertFailsWith<ApiException> { Validation.kdf(KdfParams(type = "md5", n = 1 shl 17, r = 8, p = 1, salt = salt)) }
        assertFailsWith<ApiException> { Validation.kdf(KdfParams(n = 1 shl 17, r = 8, p = 1, salt = "AAAA")) }
    }

    @Test
    fun changeValidation() {
        Validation.change(SyncChange("todo", "abc123", 0, modifiedAt = 1, payload = "v1.xx"))
        Validation.change(SyncChange("todo", "abc123", 3, deleted = true, modifiedAt = 1))
        assertFailsWith<ApiException> { Validation.change(SyncChange("todo", "abc123", 0, modifiedAt = 1)) }
        assertFailsWith<ApiException> { Validation.change(SyncChange("Todo!", "abc", 0, modifiedAt = 1, payload = "x")) }
        assertFailsWith<ApiException> { Validation.change(SyncChange("todo", "a/b", 0, modifiedAt = 1, payload = "x")) }
    }
}
