package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
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
     * One-shot damage multiplier (whole percent) staged by an `ActiveAbility`
     * with `effectKind = SCALE_NEXT_ATTACK`. Read and cleared by the next
     * successful [AttackReducer] call from this agent. Percent-encoded so a
     * 1.5× perk lands as `150` without floating-point drift.
     *
     * TODO(active-ability-buff-expiry): the staged buff has no time bound — an
     * agent who casts Power Strike and then crafts/logs out for hours pops the
     * buff on the eventual next attack. Add a `(pct, expiresAtTick)` shape and
     * discard on read past expiry, or clear on unspawn / weapon swap.
     */
    val pendingAttackScales: Map<AgentId, Int> = emptyMap(),
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
        copy(killStreaks = killStreaks + (agent to streak))

    fun stagePendingAttackScale(agent: AgentId, multiplierPct: Int): WorldState =
        copy(pendingAttackScales = pendingAttackScales + (agent to multiplierPct))

    fun consumePendingAttackScale(agent: AgentId): Pair<WorldState, Int?> {
        val scale = pendingAttackScales[agent] ?: return this to null
        return copy(pendingAttackScales = pendingAttackScales - agent) to scale
    }

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

    companion object {
        val EMPTY = WorldState(
            regions = emptyMap(),
            nodes = emptyMap(),
            positions = emptyMap(),
            bodies = emptyMap(),
            inventories = emptyMap(),
            killStreaks = emptyMap(),
            pendingAttackScales = emptyMap(),
        )
    }
}
