package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import org.jooq.DSLContext
import org.springframework.stereotype.Component

internal interface WorldOnlinePresence {
    /** Agents with an `active = true` row in [worldId]. */
    fun onlineIn(worldId: WorldId): Set<AgentId>
}

@Component
internal class JooqWorldOnlinePresence(
    private val dsl: DSLContext,
) : WorldOnlinePresence {

    override fun onlineIn(worldId: WorldId): Set<AgentId> =
        dsl.select(AGENT_POSITIONS.AGENT_ID)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.WORLD_ID.eq(worldId.value))
            .and(AGENT_POSITIONS.ACTIVE.isTrue)
            .fetch { AgentId(it[AGENT_POSITIONS.AGENT_ID]!!) }
            .toSet()
}
