package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldAgentPurger
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_EQUIPMENT_INSTANCES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_NODE_MEMORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_SAFE_NODES
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqWorldAgentPurger(
    private val dsl: DSLContext,
) : WorldAgentPurger {

    @Transactional
    override fun purge(agent: AgentId) {
        dsl.deleteFrom(AGENT_EQUIPMENT_INSTANCES).where(AGENT_EQUIPMENT_INSTANCES.AGENT_ID.eq(agent.id)).execute()
        dsl.deleteFrom(AGENT_INVENTORY).where(AGENT_INVENTORY.AGENT_ID.eq(agent.id)).execute()
        dsl.deleteFrom(AGENT_NODE_MEMORY).where(AGENT_NODE_MEMORY.AGENT_ID.eq(agent.id)).execute()
        dsl.deleteFrom(AGENT_SAFE_NODES).where(AGENT_SAFE_NODES.AGENT_ID.eq(agent.id)).execute()
        dsl.deleteFrom(AGENT_BODIES).where(AGENT_BODIES.AGENT_ID.eq(agent.id)).execute()
        dsl.deleteFrom(AGENT_POSITIONS).where(AGENT_POSITIONS.AGENT_ID.eq(agent.id)).execute()
    }
}
