package com.devvault.server.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// 表结构由 Flyway 迁移（resources/db/migration）维护，这里只做映射

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 64)
    val displayName = varchar("display_name", 64)
    val email = varchar("email", 128).nullable()
    val authHash = varchar("auth_hash", 255)
    val kdfType = varchar("kdf_type", 16)
    val kdfN = integer("kdf_n")
    val kdfR = integer("kdf_r")
    val kdfP = integer("kdf_p")
    val kdfSalt = varchar("kdf_salt", 128)
    val vaultKey = text("vault_key")
    val revision = long("revision")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Sessions : Table("sessions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val tokenHash = varchar("token_hash", 64)
    val device = varchar("device", 128)
    val createdAt = datetime("created_at")
    val lastUsedAt = datetime("last_used_at")
    val expiresAt = datetime("expires_at")

    override val primaryKey = PrimaryKey(id)
}

object SyncItems : Table("sync_items") {
    val userId = long("user_id")
    val kind = varchar("kind", 32)
    val itemId = varchar("item_id", 64)
    val revision = long("revision")
    val deleted = bool("deleted")
    val modifiedAt = long("modified_at")
    val payload = mediumText("payload").nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(userId, kind, itemId)
}

object ServerMeta : Table("server_meta") {
    val key = varchar("meta_key", 64)
    val value = text("meta_value")

    override val primaryKey = PrimaryKey(key)
}
