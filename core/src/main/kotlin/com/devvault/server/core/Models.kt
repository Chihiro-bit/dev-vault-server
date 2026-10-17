package com.devvault.server.core

import kotlinx.serialization.Serializable

/*
 * HTTP 接口模型，字段说明见客户端仓库的 docs/server-api.md：
 * https://github.com/Chihiro-bit/devvault/blob/main/docs/server-api.md
 *
 * 端到端加密约定：服务端只保存客户端加密后的数据，不接触明文和用户的主密码。
 * - authKey：客户端由主密码派生的登录凭据（与加密密钥分属不同派生分支），服务端再做一次慢哈希后保存
 * - encryptedVaultKey：用主密码派生密钥包裹后的数据密钥，服务端原样保存、原样返回
 * - payload：每条同步记录的密文，服务端不解析
 */

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    /** 接口版本，客户端据此判断是否兼容 */
    val apiVersion: Int,
    val allowRegistration: Boolean,
)

@Serializable
data class KdfParams(
    val type: String = "scrypt",
    val n: Int,
    val r: Int,
    val p: Int,
    /** base64 */
    val salt: String,
)

@Serializable
data class PreloginRequest(val username: String)

@Serializable
data class PreloginResponse(val kdf: KdfParams)

@Serializable
data class RegisterRequest(
    val username: String,
    val name: String = "",
    val email: String? = null,
    val kdf: KdfParams,
    val authKey: String,
    val encryptedVaultKey: String,
    val device: String = "",
)

@Serializable
data class LoginRequest(
    val username: String,
    val authKey: String,
    val device: String = "",
)

@Serializable
data class UserDto(val id: String, val name: String, val username: String, val email: String? = null)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserDto,
    val kdf: KdfParams,
    val encryptedVaultKey: String,
)

@Serializable
data class ChangePasswordRequest(
    val currentAuthKey: String,
    val kdf: KdfParams,
    val authKey: String,
    val encryptedVaultKey: String,
)

/** 一条同步记录。deleted=true 时 payload 为 null（墓碑） */
@Serializable
data class SyncItem(
    val kind: String,
    val id: String,
    val revision: Long,
    val deleted: Boolean,
    /** 客户端修改时间（毫秒时间戳），用于客户端解决冲突 */
    val modifiedAt: Long,
    val payload: String? = null,
)

@Serializable
data class PullResponse(
    val items: List<SyncItem>,
    /** 下次拉取时作为 since 传入 */
    val revision: Long,
    val hasMore: Boolean,
)

@Serializable
data class SyncChange(
    val kind: String,
    val id: String,
    /** 客户端最后一次看到的该记录版本号，新记录为 0 */
    val baseRevision: Long,
    val deleted: Boolean = false,
    val modifiedAt: Long,
    val payload: String? = null,
)

@Serializable
data class PushRequest(val changes: List<SyncChange>)

@Serializable
data class ItemRef(val kind: String, val id: String, val revision: Long)

@Serializable
data class PushResponse(
    val accepted: List<ItemRef>,
    /** 服务端版本已更新（被其他设备修改），客户端需先拉取再重试；revision 为服务端当前版本 */
    val conflicts: List<ItemRef>,
    val revision: Long,
)
