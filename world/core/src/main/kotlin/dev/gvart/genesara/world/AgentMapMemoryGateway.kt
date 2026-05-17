package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Per-agent map memory — what nodes has this agent had in vision, and when?
 *
 * Backed by `world.agent_node_memory`. Recorded on every `look_around` call
 * (current node + every adjacent visible node). The terrain at the time of
 * sighting is captured so a stale recall still reflects what the agent *saw*,
 * not whatever the tile looks like now after admin re-paint or some future
 * world-shaping event.
 *
 * The memory is best-effort by design: if an agent moves and disconnects
 * before their next `look_around`, the new tile won't be recorded. Phase 0
 * doesn't try to plug that gap — `move` outcomes already arrive on the event
 * stream, and the rare missing entry resolves on the next `look_around`.
 */
interface AgentMapMemoryGateway {

    /**
     * Batch-upsert visibility for [agentId] at [tick]. For new (agent, node)
     * pairs both `first_seen_tick` and `last_seen_tick` are set to [tick];
     * for existing pairs only `last_seen_tick` and `last_terrain` advance.
     *
     * Idempotent on `(agent_id, node_id)`. Calling with an empty [updates]
     * is a no-op.
     */
    fun recordVisible(agentId: AgentId, updates: Collection<NodeMemoryUpdate>, tick: Long)

    /**
     * Returns every recalled node for [agentId], ordered by `node_id` for a
     * stable response shape. Empty for fresh / never-look-aroundd agents.
     */
    fun recall(agentId: AgentId): List<RecalledNode>
}

/**
 * A single visibility record fed into [AgentMapMemoryGateway.recordVisible].
 * Both [terrain] and [biome] are snapshotted at sighting time so stale recalls
 * reflect what the agent actually saw, not the tile's current state.
 */
data class NodeMemoryUpdate(
    val nodeId: NodeId,
    val terrain: Terrain,
    val biome: Biome?,
)

/** A single map-memory entry returned by [AgentMapMemoryGateway.recall]. */
data class RecalledNode(
    val nodeId: NodeId,
    val regionId: RegionId,
    val q: Int,
    val r: Int,
    /** Terrain captured at the most recent sighting. May be stale relative to the live tile. */
    val terrain: Terrain,
    /** Biome captured at the most recent sighting. May be stale; null when the region was unpainted. */
    val biome: Biome?,
    val firstSeenTick: Long,
    val lastSeenTick: Long,
)
