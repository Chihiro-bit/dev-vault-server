package com.devvault.server.service

import com.devvault.server.core.ItemRef
import com.devvault.server.core.PullResponse
import com.devvault.server.core.PushRequest
import com.devvault.server.core.PushResponse
import com.devvault.server.core.Validation
import com.devvault.server.core.require400
import com.devvault.server.persistence.DatabaseFactory
import com.devvault.server.persistence.SyncRepository
import com.devvault.server.persistence.UserRepository

/**
 * 端到端加密同步：服务端是一个按用户隔离、带版本号的密文记录仓库。
 *
 * - 每个用户有一个单调递增的版本号，每次写入记录都会分配新的版本号
 * - 拉取：返回版本号大于 since 的所有记录（含删除墓碑）
 * - 推送：乐观并发控制，记录的 baseRevision 必须等于服务端当前版本，否则视为冲突，
 *   由客户端拉取最新数据、解密后自行合并再重新推送（服务端看不到明文，无法合并）
 */
class SyncService(private val db: DatabaseFactory) {

    suspend fun pull(auth: AuthUser, since: Long, limit: Int): PullResponse {
        require400(since >= 0) { "since 不合法" }
        val size = limit.coerceIn(1, Validation.MAX_PULL_LIMIT)
        return db.tx {
            // 先取当前版本号，再读取不超过它的记录，保证同一快照内的一致性
            val head = UserRepository.revision(auth.userId)
            val items = SyncRepository.changesSince(auth.userId, since, head, size + 1)
            val hasMore = items.size > size
            val page = if (hasMore) items.take(size) else items
            PullResponse(items = page, revision = if (hasMore) page.last().revision else head, hasMore = hasMore)
        }
    }

    suspend fun push(auth: AuthUser, req: PushRequest): PushResponse {
        require400(req.changes.size <= Validation.MAX_PUSH_CHANGES) { "单次最多推送 ${Validation.MAX_PUSH_CHANGES} 条记录" }
        val changes = req.changes.map(Validation::change)
        require400(changes.distinctBy { it.kind to it.id }.size == changes.size) { "推送中存在重复记录" }

        return db.tx {
            var revision = UserRepository.lockRevision(auth.userId)
            val current = changes.groupBy { it.kind }.flatMap { (kind, list) ->
                SyncRepository.revisions(auth.userId, kind, list.map { it.id }).map { (id, rev) -> (kind to id) to rev }
            }.toMap()

            val accepted = mutableListOf<ItemRef>()
            val conflicts = mutableListOf<ItemRef>()
            for (c in changes) {
                val serverRev = current[c.kind to c.id] ?: 0L
                if (serverRev != c.baseRevision) {
                    conflicts += ItemRef(c.kind, c.id, serverRev)
                    continue
                }
                revision += 1
                SyncRepository.write(auth.userId, c, revision)
                accepted += ItemRef(c.kind, c.id, revision)
            }
            if (accepted.isNotEmpty()) UserRepository.setRevision(auth.userId, revision)
            PushResponse(accepted, conflicts, revision)
        }
    }
}
