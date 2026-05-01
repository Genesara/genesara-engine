package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_SAFE_NODES
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqAgentSafeNodeGateway(
    private val dsl: DSLContext,
) : AgentSafeNodeGateway {

    @Transactional
    override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {
        // Upsert on agent_id — checkpoints overwrite. Same write shape as
        // JooqAgentMapMemoryGateway.recordVisible: per-row INSERT ... ON CONFLICT
        // doUpdate. The set_at_tick column re-anchors so callers can ask "when
        // did this agent last bind a safe node" if telemetry needs it.
        dsl.insertInto(AGENT_SAFE_NODES)
            .set(AGENT_SAFE_NODES.AGENT_ID, agentId.id)
            .set(AGENT_SAFE_NODES.NODE_ID, nodeId.value)
            .set(AGENT_SAFE_NODES.SET_AT_TICK, tick)
            .onConflict(AGENT_SAFE_NODES.AGENT_ID)
            .doUpdate()
            .set(AGENT_SAFE_NODES.NODE_ID, nodeId.value)
            .set(AGENT_SAFE_NODES.SET_AT_TICK, tick)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun find(agentId: AgentId): NodeId? =
        dsl.select(AGENT_SAFE_NODES.NODE_ID)
            .from(AGENT_SAFE_NODES)
            .where(AGENT_SAFE_NODES.AGENT_ID.eq(agentId.id))
            .fetchOne(AGENT_SAFE_NODES.NODE_ID)
            ?.let(::NodeId)

    @Transactional
    override fun clear(agentId: AgentId) {
        dsl.deleteFrom(AGENT_SAFE_NODES)
            .where(AGENT_SAFE_NODES.AGENT_ID.eq(agentId.id))
            .execute()
    }
}
