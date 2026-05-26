package dev.gvart.genesara.api.internal.rest.admin.audit

import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/admin/audit")
internal class AdminAuditController(
    private val auditLog: AdminAuditLog,
) {

    @GetMapping
    fun list(
        @RequestParam(name = "after", defaultValue = "0") after: Long,
        @RequestParam(name = "limit", defaultValue = "100") limit: Int,
    ): AdminAuditPage {
        if (after < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "after must be >= 0")
        if (limit !in 1..MAX_LIMIT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be in 1..$MAX_LIMIT")
        }
        val entries = auditLog.readAfter(after, limit).map(AdminAuditEntry::toDto)
        return AdminAuditPage(
            entries = entries,
            nextCursor = entries.lastOrNull()?.seq,
        )
    }

    private companion object {
        const val MAX_LIMIT = 500
    }
}

data class AdminAuditPage(
    val entries: List<AdminAuditEntryDto>,
    val nextCursor: Long?,
)

data class AdminAuditEntryDto(
    val seq: Long,
    val adminId: UUID,
    val action: String,
    val target: String,
    val targetId: String?,
    val payload: Map<String, Any?>,
    val tick: Long,
    val occurredAt: Instant,
)

private fun AdminAuditEntry.toDto() = AdminAuditEntryDto(
    seq = seq,
    adminId = adminId.id,
    action = action,
    target = target,
    targetId = targetId,
    payload = payload,
    tick = tick,
    occurredAt = occurredAt,
)
