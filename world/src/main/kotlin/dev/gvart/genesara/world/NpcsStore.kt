package dev.gvart.genesara.world

import java.util.UUID

/**
 * Per-instance NPC store. Owns spawn-row identity in Postgres; live HP and
 * `lastAttackTick` are mirrored into Redis-resident WorldState per tick. On
 * reload the in-memory mirror restores HP to `hpMax` — Tier-A fauna are
 * zone-rest respawning so the cost of per-tick HP write traffic isn't worth
 * paying.
 */
interface NpcsStore {
    fun insert(npc: Npc)
    fun findById(npcId: NpcId): Npc?
    /** Every NPC currently positioned in any of [nodeIds]. Active-set load. */
    fun byNodes(nodeIds: Collection<NodeId>): List<Npc>
    /** Atomically delete the row. Returns true when a row was deleted. */
    fun delete(npcId: NpcId): Boolean
    /** Live count of NPCs in [nodeId] — used by the spawn check to decide reseed. */
    fun countAtNode(nodeId: NodeId): Int
    /**
     * Persist mutations for an NPC loaded via [byNodes]: updated node (flee),
     * hp_current (damage), last_attack_tick (post-swing). hp_max and spawn_node_id
     * are immutable post-spawn, so they're not touched.
     */
    fun update(npc: Npc)
}

/**
 * Read-only view of `nodes.last_cleared_tick`. The lazy-on-entry spawn hook
 * uses this to decide whether to reseed; the AI sweep / death path advances
 * it via [setLastClearedTick] when the last NPC at a node dies.
 */
interface NodeClearedTimestampStore {
    fun lastClearedTick(nodeId: NodeId): Long
    fun setLastClearedTick(nodeId: NodeId, tick: Long)
}
