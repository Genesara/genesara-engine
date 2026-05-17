package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Per-agent safe-node / checkpoint storage.
 *
 * Backed by `world.agent_safe_nodes`. An agent calls the `set_safe_node` MCP
 * tool while standing at a node to mark it as their respawn point; on death
 * the `respawn` reducer reads this row to find where to materialize. Falls
 * back to the race-keyed starter node and then a random spawnable node when
 * the agent has never set a checkpoint.
 *
 * One row per agent — `set` overwrites whatever was there.
 *
 * Future: Phase-3 clan homes / cities will become additional safe-node
 * sources. The expected layout is to keep this gateway as the per-agent
 * override and layer the clan / city precedence above it in the lookup
 * (clan home > agent's last city > agent's set checkpoint > race starter >
 * random spawnable).
 */
interface AgentSafeNodeGateway {

    /**
     * Mark [nodeId] as [agentId]'s checkpoint at [tick]. Idempotent on
     * `(agent_id)` — replaces any existing entry.
     */
    fun set(agentId: AgentId, nodeId: NodeId, tick: Long)

    /** Returns the agent's current checkpoint, or null if they've never set one. */
    fun find(agentId: AgentId): NodeId?

    /**
     * Drop the agent's checkpoint row, if any. Used by the respawn reducer to
     * self-heal a stale checkpoint pointing at a deleted node — without it,
     * the agent would be stuck unable to respawn.
     */
    fun clear(agentId: AgentId)
}
