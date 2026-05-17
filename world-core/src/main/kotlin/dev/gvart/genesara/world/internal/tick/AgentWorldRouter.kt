package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import org.jooq.DSLContext
import org.springframework.stereotype.Component

internal interface AgentWorldRouter {
    /**
     * The world an agent's command should be queued against. Reads
     * `agent_positions.world_id` regardless of `active` so a returning
     * agent's `spawn` lands in the same world they last left.
     *
     * On first-ever submission (no `agent_positions` row yet) falls back to
     * the lowest-id world in the `worlds` table. Correct for single-world
     * deployments. Multi-world spawn routing (race-keyed starter-world
     * picked at submit time) is a follow-up; if the fallback picks the
     * wrong world for a non-spawn command the reducer rejects with
     * `NotInWorld`, and a wrong-world spawn command lands in the lease
     * holder's per-world `WorldStateRepository.load` slice and resolves
     * via `SpawnLocationResolver` against that world's nodes — surfacing
     * the mismatch as `NoSpawnableNode` rather than silent loss.
     */
    fun routeFor(agent: AgentId): WorldId?
}

@Component
internal class JooqAgentWorldRouter(
    private val dsl: DSLContext,
) : AgentWorldRouter {

    override fun routeFor(agent: AgentId): WorldId? {
        val known = dsl.select(AGENT_POSITIONS.WORLD_ID)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.AGENT_ID.eq(agent.id))
            .fetchOne(AGENT_POSITIONS.WORLD_ID)
        if (known != null) return WorldId(known)

        return dsl.select(WORLDS.ID)
            .from(WORLDS)
            .orderBy(WORLDS.ID.asc())
            .limit(1)
            .fetchOne(WORLDS.ID)
            ?.let(::WorldId)
    }
}
