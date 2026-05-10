package dev.gvart.genesara.world.internal.behavior

import dev.gvart.genesara.player.AgentId

/**
 * Silent per-agent action counters that feed the level-10 class-choice
 * fingerprint (#33) and, later, the L50 evolution scoring (#34).
 *
 * Contract — counters are **never** exposed to agents: no MCP tool, projection,
 * or `get_status` field surfaces them. The interface stays `internal` to the
 * world module so an accidental import from `:api` fails to compile.
 */
internal interface BehaviorTracker {

    fun record(agent: AgentId, category: ActionCategory, tick: Long)

    /** Cumulative count per category; categories the agent has never touched are absent. */
    fun snapshotFor(agent: AgentId): Map<ActionCategory, Int>
}
