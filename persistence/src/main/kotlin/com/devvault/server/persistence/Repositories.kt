package com.devvault.server.persistence

import com.devvault.server.core.KdfParams
import com.devvault.server.core.SyncChange
import com.devvault.server.core.SyncItem
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDateTime
import java.time.ZoneOffset

// 仓储方法均需在 DatabaseFactory.tx { } 内调用

internal fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

data class UserRecord(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String?,
    val authHash: String,
    val kdf: KdfParams,
    val vaultKey: String,
)

object UserRepository {
    private fun ResultRow.toUser() = UserRecord(
        id = this[Users.id],
        username = this[Users.username],
        displayName = this[Users.displayName],
        email = this[Users.email],
        authHash = this[Users.authHash],
        kdf = KdfParams(
            type = this[Users.kdfType],
            n = this[Users.kdfN],
            r = this[Users.kdfR],
            p = this[Users.kdfP],
            salt = this[Users.kdfSalt],
        ),
        vaultKey = this[Users.vaultKey],
    )

    fun findByUsername(username: String): UserRecord? =
        Users.selectAll().where { Users.username eq username }.singleOrNull()?.toUser()

    fun findById(id: Long): UserRecord? =
        Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser()

    fun create(username: String, displayName: String, email: String?, authHash: String, kdf: KdfParams, vaultKey: String): Long {
        val now = utcNow()
        return Users.insert {
            it[Users.username] = username
            it[Users.displayName] = displayName
            it[Users.email] = email
            it[Users.authHash] = authHash
            it[kdfType] = kdf.type
            it[kdfN] = kdf.n
            it[kdfR] = kdf.r
            it[kdfP] = kdf.p
            it[kdfSalt] = kdf.salt
            it[Users.vaultKey] = vaultKey
            it[revision] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }[Users.id]
    }

    fun updateCredentials(id: Long, authHash: String, kdf: KdfParams, vaultKey: String) {
        Users.update({ Users.id eq id }) {
            it[Users.authHash] = authHash
            it[kdfType] = kdf.type
            it[kdfN] = kdf.n
            it[kdfR] = kdf.r
            it[kdfP] = kdf.p
            it[kdfSalt] = kdf.salt
            it[Users.vaultKey] = vaultKey
            it[updatedAt] = utcNow()
        }
    }

    fun revision(id: Long): Long =
        Users.select(Users.revision).where { Users.id eq id }.single()[Users.revision]

    /** 锁住用户行并返回当前版本号，用于串行化同一用户的并发推送 */
    fun lockRevision(id: Long): Long =
        Users.select(Users.revision).where { Users.id eq id }.forUpdate().single()[Users.revision]

    fun setRevision(id: Long, revision: Long) {
        Users.update({ Users.id eq id }) { it[Users.revision] = revision }
    }
}

data class SessionRecord(val id: Long, val userId: Long, val lastUsedAt: LocalDateTime, val expiresAt: LocalDateTime)

object SessionRepository {
    fun create(userId: Long, tokenHash: String, device: String, expiresAt: LocalDateTime): Long {
        val now = utcNow()
        return Sessions.insert {
            it[Sessions.userId] = userId
            it[Sessions.tokenHash] = tokenHash
            it[Sessions.device] = device
            it[createdAt] = now
            it[lastUsedAt] = now
            it[Sessions.expiresAt] = expiresAt
        }[Sessions.id]
    }

    fun findByTokenHash(tokenHash: String): SessionRecord? =
        Sessions.selectAll().where { Sessions.tokenHash eq tokenHash }.singleOrNull()?.let {
            SessionRecord(it[Sessions.id], it[Sessions.userId], it[Sessions.lastUsedAt], it[Sessions.expiresAt])
        }

    fun touch(id: Long, expiresAt: LocalDateTime) {
        Sessions.update({ Sessions.id eq id }) {
            it[lastUsedAt] = utcNow()
            it[Sessions.expiresAt] = expiresAt
        }
    }

    fun deleteByTokenHash(tokenHash: String) {
        Sessions.deleteWhere { Sessions.tokenHash eq tokenHash }
    }

    fun deleteOthers(userId: Long, keepId: Long) {
        Sessions.deleteWhere { (Sessions.userId eq userId) and (Sessions.id neq keepId) }
    }

    fun purgeExpired(): Int = Sessions.deleteWhere { expiresAt less utcNow() }
}

object SyncRepository {
    private fun ResultRow.toItem() = SyncItem(
        kind = this[SyncItems.kind],
        id = this[SyncItems.itemId],
        revision = this[SyncItems.revision],
        deleted = this[SyncItems.deleted],
        modifiedAt = this[SyncItems.modifiedAt],
        payload = this[SyncItems.payload],
    )

    /** 版本号在 (since, upTo] 区间内的记录，按版本号升序 */
    fun changesSince(userId: Long, since: Long, upTo: Long, limit: Int): List<SyncItem> =
        SyncItems.selectAll()
            .where { (SyncItems.userId eq userId) and (SyncItems.revision greater since) and (SyncItems.revision lessEq upTo) }
            .orderBy(SyncItems.revision to SortOrder.ASC)
            .limit(limit)
            .map { it.toItem() }

    /** 查询已存在记录的当前版本号，key 为 kind/id */
    fun revisions(userId: Long, kind: String, ids: Collection<String>): Map<String, Long> {
        if (ids.isEmpty()) return emptyMap()
        return SyncItems.select(SyncItems.itemId, SyncItems.revision)
            .where { (SyncItems.userId eq userId) and (SyncItems.kind eq kind) and (SyncItems.itemId inList ids) }
            .associate { it[SyncItems.itemId] to it[SyncItems.revision] }
    }

    fun write(userId: Long, change: SyncChange, revision: Long) {
        SyncItems.upsert {
            it[SyncItems.userId] = userId
            it[kind] = change.kind
            it[itemId] = change.id
            it[SyncItems.revision] = revision
            it[deleted] = change.deleted
            it[modifiedAt] = change.modifiedAt
            it[payload] = if (change.deleted) null else change.payload
            it[updatedAt] = utcNow()
        }
    }
}

object MetaRepository {
    fun get(key: String): String? =
        ServerMeta.selectAll().where { ServerMeta.key eq key }.singleOrNull()?.get(ServerMeta.value)

    /** 不存在时写入并返回 value；已存在则返回已有值（多实例同时启动也只会有一个值生效） */
    fun getOrPut(key: String, value: () -> String): String {
        get(key)?.let { return it }
        val v = value()
        ServerMeta.upsert(onUpdateExclude = listOf(ServerMeta.value)) {
            it[ServerMeta.key] = key
            it[ServerMeta.value] = v
        }
        return get(key)!!
    }
}
