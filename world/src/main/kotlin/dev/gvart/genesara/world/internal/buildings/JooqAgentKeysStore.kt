package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKeyInstance
import dev.gvart.genesara.world.AgentKeysStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_KEYS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqAgentKeysStore(
    private val dsl: DSLContext,
) : AgentKeysStore {

    @Transactional
    override fun insert(key: AgentKeyInstance) {
        dsl.insertInto(AGENT_KEYS)
            .set(AGENT_KEYS.INSTANCE_ID, key.instanceId)
            .set(AGENT_KEYS.AGENT_ID, key.agentId.id)
            .set(AGENT_KEYS.ITEM_ID, key.itemId.value)
            .set(AGENT_KEYS.GATE_INSTANCE_ID, key.gateInstanceId)
            .set(AGENT_KEYS.CREATED_AT_TICK, key.createdAtTick)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun findById(instanceId: UUID): AgentKeyInstance? =
        dsl.selectFrom(AGENT_KEYS)
            .where(AGENT_KEYS.INSTANCE_ID.eq(instanceId))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(AGENT_KEYS)
                .where(AGENT_KEYS.AGENT_ID.eq(agent.id))
                .and(AGENT_KEYS.GATE_INSTANCE_ID.eq(gateInstanceId)),
        )

    @Transactional(readOnly = true)
    override fun listByAgent(agent: AgentId): List<AgentKeyInstance> =
        dsl.selectFrom(AGENT_KEYS)
            .where(AGENT_KEYS.AGENT_ID.eq(agent.id))
            .orderBy(AGENT_KEYS.CREATED_AT_TICK.asc(), AGENT_KEYS.INSTANCE_ID.asc())
            .fetch(::toDomain)

    private fun toDomain(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.AgentKeysRecord,
    ): AgentKeyInstance = AgentKeyInstance(
        instanceId = record.instanceId,
        agentId = AgentId(record.agentId),
        itemId = ItemId(record.itemId),
        gateInstanceId = record.gateInstanceId,
        createdAtTick = record.createdAtTick,
    )
}
