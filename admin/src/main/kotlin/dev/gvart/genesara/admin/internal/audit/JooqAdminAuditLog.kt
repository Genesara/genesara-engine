package dev.gvart.genesara.admin.internal.audit

import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.admin.internal.jooq.tables.references.ADMIN_AUDIT_LOG
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.Record
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
internal class JooqAdminAuditLog(
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) : AdminAuditLog {

    @Transactional
    override fun record(
        adminId: AdminId,
        action: String,
        target: String,
        targetId: String?,
        payload: Map<String, Any?>,
        tick: Long,
    ): Long =
        dsl.insertInto(ADMIN_AUDIT_LOG)
            .set(ADMIN_AUDIT_LOG.ADMIN_ID, adminId.id)
            .set(ADMIN_AUDIT_LOG.ACTION, action)
            .set(ADMIN_AUDIT_LOG.TARGET, target)
            .set(ADMIN_AUDIT_LOG.TARGET_ID, targetId)
            .set(ADMIN_AUDIT_LOG.PAYLOAD, encode(payload))
            .set(ADMIN_AUDIT_LOG.TICK, tick)
            .returning(ADMIN_AUDIT_LOG.SEQ)
            .fetchOne()
            ?.get(ADMIN_AUDIT_LOG.SEQ)
            ?: error("admin_audit_log insert returned no seq")

    @Transactional(readOnly = true)
    override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> {
        require(limit > 0) { "limit must be positive" }
        return dsl.selectFrom(ADMIN_AUDIT_LOG)
            .where(ADMIN_AUDIT_LOG.SEQ.gt(after))
            .orderBy(ADMIN_AUDIT_LOG.SEQ.asc())
            .limit(limit)
            .fetch()
            .map(::toDomain)
    }

    private fun encode(payload: Map<String, Any?>): JSON =
        JSON.valueOf(mapper.writeValueAsString(payload))

    private fun decode(json: JSON): Map<String, Any?> =
        mapper.readValue(json.data(), PAYLOAD_TYPE)

    private fun toDomain(record: Record): AdminAuditEntry = AdminAuditEntry(
        seq = record[ADMIN_AUDIT_LOG.SEQ]!!,
        adminId = AdminId(record[ADMIN_AUDIT_LOG.ADMIN_ID]!!),
        action = record[ADMIN_AUDIT_LOG.ACTION]!!,
        target = record[ADMIN_AUDIT_LOG.TARGET]!!,
        targetId = record[ADMIN_AUDIT_LOG.TARGET_ID],
        payload = decode(record[ADMIN_AUDIT_LOG.PAYLOAD]!!),
        tick = record[ADMIN_AUDIT_LOG.TICK]!!,
        occurredAt = record[ADMIN_AUDIT_LOG.OCCURRED_AT]!!.toInstant(),
    )

    private companion object {
        private val PAYLOAD_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
