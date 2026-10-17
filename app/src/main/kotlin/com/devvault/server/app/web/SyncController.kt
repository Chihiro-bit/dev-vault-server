package com.devvault.server.app.web

import com.devvault.server.core.PullResponse
import com.devvault.server.core.PushRequest
import com.devvault.server.core.PushResponse
import com.devvault.server.service.AuthUser
import com.devvault.server.service.SyncService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sync")
class SyncController(private val sync: SyncService) {

    /** 返回版本号大于 since 的记录，按版本号升序 */
    @GetMapping("/pull")
    fun pull(
        @AuthPrincipal user: AuthUser,
        @RequestParam(defaultValue = "0") since: Long,
        @RequestParam(defaultValue = "500") limit: Int,
    ): PullResponse = sync.pull(user, since, limit)

    /** 乐观并发写入，baseRevision 与服务端不一致时列入 conflicts */
    @PostMapping("/push")
    fun push(@AuthPrincipal user: AuthUser, @RequestBody req: PushRequest): PushResponse =
        sync.push(user, req)
}
