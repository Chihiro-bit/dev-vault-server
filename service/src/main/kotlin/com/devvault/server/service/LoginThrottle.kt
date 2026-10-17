package com.devvault.server.service

import com.devvault.server.core.ApiException
import java.util.concurrent.ConcurrentHashMap

/**
 * 登录失败限流（单实例内存实现）：同一「账号 + IP」在窗口期内连续失败 maxFailures 次后，
 * 需等到窗口结束才能再次尝试。多实例部署时可在网关层另行限流。
 */
class LoginThrottle(
    private val maxFailures: Int = 5,
    private val windowMillis: Long = 15 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Bucket(val count: Int, val since: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    private fun key(username: String, ip: String) = "$username|$ip"

    fun check(username: String, ip: String) {
        val b = buckets[key(username, ip)] ?: return
        val elapsed = clock() - b.since
        if (elapsed >= windowMillis) {
            buckets.remove(key(username, ip), b)
            return
        }
        if (b.count >= maxFailures) {
            val minutes = (windowMillis - elapsed + 59_999) / 60_000
            throw ApiException.tooManyRequests("尝试次数过多，请 $minutes 分钟后再试")
        }
    }

    fun fail(username: String, ip: String) {
        val now = clock()
        buckets.compute(key(username, ip)) { _, b ->
            if (b == null || now - b.since >= windowMillis) Bucket(1, now) else b.copy(count = b.count + 1)
        }
        if (buckets.size > 100_000) buckets.entries.removeIf { now - it.value.since >= windowMillis }
    }

    fun succeed(username: String, ip: String) {
        buckets.remove(key(username, ip))
    }
}
