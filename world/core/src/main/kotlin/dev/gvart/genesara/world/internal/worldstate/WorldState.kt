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
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.slices.CombatSlice
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import dev.gvart.genesara.world.internal.worldstate.slices.EnvironmentSlice

/**
 * The in-memory snapshot a tick folds reducers over.
 *
 * Composed of per-zone slices (ADR 0003). Backward-compatible field accessors
 * and helper methods delegate to the slices so reducer call sites that read
 * `state.positions` / `state.bodies` etc. keep working through the zone split.
 * Raw `.copy(field = ...)` mutations must go through the appropriate slice.
 */
data class WorldState(
    val core: CoreSlice,
    val body: BodySlice,
    val combat: CombatSlice,
    val environment: EnvironmentSlice,
) {

    constructor(
        regions: Map<RegionId, Region> = emptyMap(),
        nodes: Map<NodeId, Node> = emptyMap(),
        positions: Map<AgentId, NodeId> = emptyMap(),
        bodies: Map<AgentId, AgentBody> = emptyMap(),
        inventories: Map<AgentId, AgentInventory> = emptyMap(),
        killStreaks: Map<AgentId, AgentKillStreak> = emptyMap(),
        dirtyKillStreaks: Set<AgentId> = emptySet(),
        npcs: Map<NpcId, Npc> = emptyMap(),
        dirtyNpcs: Set<NpcId> = emptySet(),
        removedNpcs: Set<NpcId> = emptySet(),
        nodesClearedThisTick: Map<NodeId, Long> = emptyMap(),
    ) : this(
        core = CoreSlice(regions, nodes, positions),
        body = BodySlice(bodies, inventories),
        combat = CombatSlice(killStreaks, dirtyKillStreaks),
        environment = EnvironmentSlice(npcs, dirtyNpcs, removedNpcs, nodesClearedThisTick),
    )

    /**
     * Back-compat shim — keeps `state.copy(field = ...)` calls compiling
     * while reducers and tests still think in flat-field terms. Removed in
     * Phase 1.2 (ADR 0003) when reducers move to `ReducerOutput` and tests
     * are migrated to slice-aware fixtures.
     */
    fun copy(
        regions: Map<RegionId, Region> = this.regions,
        nodes: Map<NodeId, Node> = this.nodes,
        positions: Map<AgentId, NodeId> = this.positions,
        bodies: Map<AgentId, AgentBody> = this.bodies,
        inventories: Map<AgentId, AgentInventory> = this.inventories,
        killStreaks: Map<AgentId, AgentKillStreak> = this.killStreaks,
        dirtyKillStreaks: Set<AgentId> = this.dirtyKillStreaks,
        npcs: Map<NpcId, Npc> = this.npcs,
        dirtyNpcs: Set<NpcId> = this.dirtyNpcs,
        removedNpcs: Set<NpcId> = this.removedNpcs,
        nodesClearedThisTick: Map<NodeId, Long> = this.nodesClearedThisTick,
    ): WorldState = copy(
        core = CoreSlice(regions, nodes, positions),
        body = BodySlice(bodies, inventories),
        combat = CombatSlice(killStreaks, dirtyKillStreaks),
        environment = EnvironmentSlice(npcs, dirtyNpcs, removedNpcs, nodesClearedThisTick),
    )

    val regions: Map<RegionId, Region> get() = core.regions
    val nodes: Map<NodeId, Node> get() = core.nodes
    val positions: Map<AgentId, NodeId> get() = core.positions
    val bodies: Map<AgentId, AgentBody> get() = body.bodies
    val inventories: Map<AgentId, AgentInventory> get() = body.inventories
    val killStreaks: Map<AgentId, AgentKillStreak> get() = combat.killStreaks
    val dirtyKillStreaks: Set<AgentId> get() = combat.dirtyKillStreaks
    val npcs: Map<NpcId, Npc> get() = environment.npcs
    val dirtyNpcs: Set<NpcId> get() = environment.dirtyNpcs
    val removedNpcs: Set<NpcId> get() = environment.removedNpcs
    val nodesClearedThisTick: Map<NodeId, Long> get() = environment.nodesClearedThisTick

    fun isAdjacent(from: NodeId, to: NodeId): Boolean =
        core.nodes[from]?.adjacency?.contains(to) == true

    fun moveAgent(agent: AgentId, to: NodeId): WorldState =
        copy(core = core.copy(positions = core.positions + (agent to to)))

    fun bodyOf(agent: AgentId): AgentBody? = body.bodies[agent]

    fun isOnline(agent: AgentId): Boolean = agent in core.positions

    fun updateBody(agent: AgentId, body: AgentBody): WorldState =
        copy(body = this.body.copy(bodies = this.body.bodies + (agent to body)))

    fun inventoryOf(agent: AgentId): AgentInventory =
        body.inventories[agent] ?: AgentInventory.EMPTY

    fun updateInventory(agent: AgentId, inventory: AgentInventory): WorldState =
        copy(body = body.copy(inventories = body.inventories + (agent to inventory)))

    fun killStreakOf(agent: AgentId): AgentKillStreak =
        combat.killStreaks[agent] ?: AgentKillStreak.EMPTY

    fun updateKillStreak(agent: AgentId, streak: AgentKillStreak): WorldState =
        copy(
            combat = combat.copy(
                killStreaks = combat.killStreaks + (agent to streak),
                dirtyKillStreaks = combat.dirtyKillStreaks + agent,
            ),
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

    fun npcsAt(node: NodeId): List<Npc> = environment.npcs.values.filter { it.nodeId == node }

    fun updateNpc(npc: Npc): WorldState =
        copy(
            environment = environment.copy(
                npcs = environment.npcs + (npc.id to npc),
                dirtyNpcs = environment.dirtyNpcs + npc.id,
            ),
        )

    /**
     * Remove the NPC and, if it was the last one at its node, advance
     * `last_cleared_tick` to [tick] so the lazy-spawn timer starts counting
     * down from the moment the node actually emptied.
     */
    fun removeNpc(npcId: NpcId, tick: Long): WorldState {
        val npc = environment.npcs[npcId] ?: return this
        val nextNpcs = environment.npcs - npcId
        val sameNodeRemaining = nextNpcs.values.any { it.nodeId == npc.nodeId }
        val cleared = if (!sameNodeRemaining) {
            environment.nodesClearedThisTick + (npc.nodeId to tick)
        } else {
            environment.nodesClearedThisTick
        }
        return copy(
            environment = environment.copy(
                npcs = nextNpcs,
                removedNpcs = environment.removedNpcs + npcId,
                dirtyNpcs = environment.dirtyNpcs - npcId,
                nodesClearedThisTick = cleared,
            ),
        )
    }

    /** Add a freshly-spawned NPC to the in-memory mirror; flush-time marks it dirty. */
    fun addSpawnedNpc(npc: Npc): WorldState =
        copy(
            environment = environment.copy(
                npcs = environment.npcs + (npc.id to npc),
                dirtyNpcs = environment.dirtyNpcs + npc.id,
            ),
        )

    companion object {
        val EMPTY = WorldState(
            core = CoreSlice.EMPTY,
            body = BodySlice.EMPTY,
            combat = CombatSlice.EMPTY,
            environment = EnvironmentSlice.EMPTY,
        )
    }
}
