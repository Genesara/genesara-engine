package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory

internal data class WorldState(
    val regions: Map<RegionId, Region>,
    val nodes: Map<NodeId, Node>,
    val positions: Map<AgentId, NodeId>,
    val bodies: Map<AgentId, AgentBody>,
    val inventories: Map<AgentId, AgentInventory>,
    val killStreaks: Map<AgentId, AgentKillStreak> = emptyMap(),
    /**
     * Agents whose kill streak the current tick's reducers actually touched.
     * `JooqWorldStateRepository.save` writes only these entries, so a quiet
     * tick (no kills) issues zero Redis round-trips even when [killStreaks]
     * is populated by the load-side projection. Without this, every online
     * agent's prior streak would be re-HSET on every tick.
     */
    val dirtyKillStreaks: Set<AgentId> = emptySet(),
    /**
     * NPCs in the active-set load (R=8 hops from any online agent's position).
     * Loaded fresh each tick from [dev.gvart.genesara.world.NpcsStore.byNodes];
     * mutations during the tick (damage, flee, last-attack-tick advance) live
     * here and flush to Postgres at save time via [dirtyNpcs] / [removedNpcs].
     */
    val npcs: Map<NpcId, Npc> = emptyMap(),
    /** NPCs the current tick mutated — flushed back to Postgres on save. */
    val dirtyNpcs: Set<NpcId> = emptySet(),
    /** NPCs that died this tick — deleted from Postgres on save. */
    val removedNpcs: Set<NpcId> = emptySet(),
    /** Nodes whose `last_cleared_tick` advanced this tick (last NPC died). */
    val nodesClearedThisTick: Map<NodeId, Long> = emptyMap(),
) {

    fun isAdjacent(from: NodeId, to: NodeId): Boolean =
        nodes[from]?.adjacency?.contains(to) == true

    fun moveAgent(agent: AgentId, to: NodeId): WorldState =
        copy(positions = positions + (agent to to))

    fun bodyOf(agent: AgentId): AgentBody? = bodies[agent]

    fun isOnline(agent: AgentId): Boolean = agent in positions

    fun updateBody(agent: AgentId, body: AgentBody): WorldState =
        copy(bodies = bodies + (agent to body))

    fun inventoryOf(agent: AgentId): AgentInventory =
        inventories[agent] ?: AgentInventory.EMPTY

    fun updateInventory(agent: AgentId, inventory: AgentInventory): WorldState =
        copy(inventories = inventories + (agent to inventory))

    fun killStreakOf(agent: AgentId): AgentKillStreak =
        killStreaks[agent] ?: AgentKillStreak.EMPTY

    fun updateKillStreak(agent: AgentId, streak: AgentKillStreak): WorldState =
        copy(
            killStreaks = killStreaks + (agent to streak),
            dirtyKillStreaks = dirtyKillStreaks + agent,
        )

    /**
     * Public-API surface for the Phase 2 combat reducer. Encapsulates the
     * rolling-window reset: a kill outside the active window starts a fresh
     * streak; a kill inside the window adds 1.
     *
     * `AgentKillStreak.EMPTY` is the explicit "no prior streak" sentinel, not a
     * live (0, 0) streak — the first kill always anchors the window to
     * [currentTick]. Without this, the very first kills at low ticks
     * (currentTick < windowTicks) would inherit `windowStartTick = 0L` from the
     * sentinel and silently expire earlier than the documented "1000 ticks
     * since your last kill" semantic.
     */
    fun incrementKillStreak(agent: AgentId, currentTick: Long, windowTicks: Long): WorldState {
        val current = killStreakOf(agent)
        val noPriorStreak = current == AgentKillStreak.EMPTY
        val windowExpired = currentTick - current.windowStartTick >= windowTicks
        val next = if (noPriorStreak || windowExpired) {
            AgentKillStreak(killCount = 1, windowStartTick = currentTick)
        } else {
            current.copy(killCount = current.killCount + 1)
        }
        return updateKillStreak(agent, next)
    }

    fun npcsAt(node: NodeId): List<Npc> = npcs.values.filter { it.nodeId == node }

    fun updateNpc(npc: Npc): WorldState =
        copy(npcs = npcs + (npc.id to npc), dirtyNpcs = dirtyNpcs + npc.id)

    /**
     * Remove the NPC and, if it was the last one at its node, advance
     * `last_cleared_tick` to [tick] so the lazy-spawn timer starts counting
     * down from the moment the node actually emptied.
     */
    fun removeNpc(npcId: NpcId, tick: Long): WorldState {
        val npc = npcs[npcId] ?: return this
        val nextNpcs = npcs - npcId
        val sameNodeRemaining = nextNpcs.values.any { it.nodeId == npc.nodeId }
        val cleared = if (!sameNodeRemaining) {
            nodesClearedThisTick + (npc.nodeId to tick)
        } else {
            nodesClearedThisTick
        }
        return copy(
            npcs = nextNpcs,
            removedNpcs = removedNpcs + npcId,
            dirtyNpcs = dirtyNpcs - npcId,
            nodesClearedThisTick = cleared,
        )
    }

    /** Add a freshly-spawned NPC to the in-memory mirror; flush-time marks it dirty. */
    fun addSpawnedNpc(npc: Npc): WorldState =
        copy(npcs = npcs + (npc.id to npc), dirtyNpcs = dirtyNpcs + npc.id)

    companion object {
        val EMPTY = WorldState(
            regions = emptyMap(),
            nodes = emptyMap(),
            positions = emptyMap(),
            bodies = emptyMap(),
            inventories = emptyMap(),
            killStreaks = emptyMap(),
        )
    }
}
