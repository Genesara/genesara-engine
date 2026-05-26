package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Operator-only mutations on an agent's body and position state. Bypasses the
 * tick-reducer loop so admins can intervene out-of-band during incidents.
 *
 * Writes hit the persisted `agent_bodies` / `agent_positions` / `agent_safe_nodes`
 * tables directly and emit matching [dev.gvart.genesara.world.events.WorldEvent]s so
 * the agent's event stream reflects the change. Mid-tick races against the live
 * world state are accepted — admin interventions are rare and operator-driven.
 *
 * All operations return a [AgentBodyAdminResult] describing what changed so the
 * controller can write the matching audit row.
 */
interface AgentBodyAdminGateway {

    /**
     * Overwrite any subset of [hp]/[stamina]/[mana]/[hunger]/[thirst]/[sleep] for [agent].
     * Each non-null value is clamped to `0..max{pool}` (max read from the body row).
     * Null fields are left untouched. No-ops on a missing agent body — returns
     * [AgentBodyAdminResult.AgentNotFound].
     */
    fun setGauges(
        agent: AgentId,
        hp: Int? = null,
        stamina: Int? = null,
        mana: Int? = null,
        hunger: Int? = null,
        thirst: Int? = null,
        sleep: Int? = null,
    ): AgentBodyAdminResult

    /**
     * Move [agent] to [nodeId], crossing worlds when the target lives in a different
     * world from the agent's current position. Updates `agent_positions.world_id`
     * atomically and emits [dev.gvart.genesara.world.events.CoreEvent.AgentMoved]
     * with `staminaSpent = 0` (admin teleports never charge stamina).
     */
    fun teleport(agent: AgentId, nodeId: NodeId, tick: Long): AgentBodyAdminResult

    /**
     * Materialize [agent] at their resolved safe node with full HP/Stamina/Mana
     * and full survival gauges. Skips the de-level + XP-loss penalty —
     * [dev.gvart.genesara.player.AgentRegistry.applyDeathPenalty] is NOT invoked.
     * Emits [dev.gvart.genesara.world.events.BodyEvent.AgentRespawned].
     *
     * Resolution order: explicit safe-node row → race-keyed starter → random spawnable.
     */
    fun forceRespawn(agent: AgentId, tick: Long): AgentBodyAdminResult
}

sealed interface AgentBodyAdminResult {
    data class GaugesUpdated(val applied: Map<String, Int>) : AgentBodyAdminResult
    data class Teleported(val from: NodeId?, val to: NodeId, val crossedWorld: Boolean) : AgentBodyAdminResult
    data class Respawned(val at: NodeId, val fromCheckpoint: Boolean) : AgentBodyAdminResult
    data class AgentNotFound(val agent: AgentId) : AgentBodyAdminResult
    data class NodeNotFound(val node: NodeId) : AgentBodyAdminResult
    data class NoSpawnableNode(val agent: AgentId) : AgentBodyAdminResult
}
