package dev.gvart.genesara.admin

import java.time.Instant

interface AdminAuditLog {
    /** Appends one audit row and returns its assigned [AdminAuditEntry.seq]. */
    fun record(
        adminId: AdminId,
        action: String,
        target: String,
        targetId: String?,
        payload: Map<String, Any?>,
        tick: Long,
    ): Long

    /** Returns up to [limit] entries with `seq > after`, ordered ascending by `seq`. */
    fun readAfter(after: Long, limit: Int): List<AdminAuditEntry>
}

data class AdminAuditEntry(
    val seq: Long,
    val adminId: AdminId,
    val action: String,
    val target: String,
    val targetId: String?,
    val payload: Map<String, Any?>,
    val tick: Long,
    val occurredAt: Instant,
)
