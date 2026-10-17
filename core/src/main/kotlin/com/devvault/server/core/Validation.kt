package com.devvault.server.core

/** 接口入参校验规则 */
object Validation {
    private val USERNAME = Regex("^[a-z0-9_.@-]{3,64}$")
    private val KIND = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val ITEM_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val ENCRYPTED = Regex("^[A-Za-z0-9+/=._:-]{1,1024}$")

    /** 单条密文上限（base64 后），附件不走同步接口 */
    const val MAX_PAYLOAD = 512 * 1024
    const val MAX_PUSH_CHANGES = 500
    const val MAX_PULL_LIMIT = 1000

    /** 登录名不区分大小写 */
    fun normalizeUsername(raw: String): String {
        val u = raw.trim().lowercase()
        require400(USERNAME.matches(u)) { "账号需为 3~64 位字母、数字或 _ . @ -" }
        return u
    }

    fun displayName(raw: String, fallback: String): String {
        val n = raw.trim().ifEmpty { fallback }
        require400(n.length <= 64) { "名称不能超过 64 个字符" }
        return n
    }

    fun email(raw: String?): String? {
        val e = raw?.trim().orEmpty()
        if (e.isEmpty()) return null
        require400(e.length <= 128 && '@' in e) { "邮箱格式不正确" }
        return e
    }

    fun device(raw: String): String = raw.trim().take(128)

    /** authKey：客户端派生的 32 字节密钥（base64） */
    fun authKey(raw: String): String {
        require400(Crypto.decodeBase64(raw)?.size == 32) { "登录凭据格式不正确，请升级客户端" }
        return raw
    }

    fun encryptedKey(raw: String): String {
        require400(ENCRYPTED.matches(raw)) { "加密密钥格式不正确" }
        return raw
    }

    /** 只接受 scrypt；参数限制在客户端能在合理时间内完成、又足够抗暴力破解的区间 */
    fun kdf(k: KdfParams): KdfParams {
        require400(k.type == "scrypt") { "不支持的密钥派生算法：${k.type}" }
        require400(k.n in (1 shl 14)..(1 shl 20) && k.n and (k.n - 1) == 0) { "scrypt 参数 N 不合法" }
        require400(k.r in 1..32 && k.p in 1..16) { "scrypt 参数 r/p 不合法" }
        val salt = Crypto.decodeBase64(k.salt)
        require400(salt != null && salt.size in 16..64) { "salt 不合法" }
        return k
    }

    fun change(c: SyncChange): SyncChange {
        require400(KIND.matches(c.kind)) { "记录类型不合法：${c.kind}" }
        require400(ITEM_ID.matches(c.id)) { "记录 id 不合法：${c.id}" }
        require400(c.baseRevision >= 0) { "baseRevision 不合法" }
        if (c.deleted) {
            require400(c.payload == null) { "删除记录不应携带 payload" }
        } else {
            val p = c.payload.orEmpty()
            require400(p.isNotEmpty()) { "记录 ${c.kind}/${c.id} 缺少 payload" }
            require400(p.length <= MAX_PAYLOAD) { "记录 ${c.kind}/${c.id} 过大" }
        }
        return c
    }
}
