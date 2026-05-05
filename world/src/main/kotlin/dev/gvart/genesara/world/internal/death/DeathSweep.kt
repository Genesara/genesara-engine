package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

private val log = LoggerFactory.getLogger("dev.gvart.genesara.world.internal.death.DeathSweep")

/**
 * Post-passives death sweep. Finds every positioned agent whose body just hit `hp <= 0`
 * (starvation today; combat in Phase 2 will land the same path) and:
 *
 *  1. Applies the canonical death penalty via [AgentRegistry.applyDeathPenalty]
 *     (partial-bar XP loss vs. empty-bar de-level + stat point — the registry decides).
 *  2. Rolls the kill-streak drop hook: if the rolling kill counter is non-zero and
 *     the seeded RNG clears [BalanceLookup.dropChanceForKillCount], one entry from
 *     the agent's drop pool (stackable inventory + currently-equipped instances) is
 *     removed and deposited at the death node via [GroundItemStore]. Other agents
 *     can pick it up later via the `pickup` MCP tool.
 *  3. Resets the dying agent's kill streak so the bonus does not survive death.
 *  4. Removes the agent from `state.positions` so they're "awaiting respawn". The
 *     body persists at HP=0; the agent must call `respawn` to materialize.
 *  5. Emits [WorldEvent.AgentDied] (penalty summary + optional `droppedItem`) and,
 *     if a drop fired, a paired [WorldEvent.ItemDroppedOnGround] so other agents
 *     get a notification independent of the dying agent's own stream.
 *
 * Body restoration happens inside the respawn reducer, not here. The split lets the
 * agent linger at the death state and forces an explicit re-entry rather than auto-
 * spawning them somewhere they didn't choose.
 *
 * Called once per tick from `WorldTickHandler` after passives apply but before the
 * per-command reducers, so a dying agent's queued actions hit `NotInWorld` rather than
 * slipping through one tick of post-mortem play.
 */
internal fun processDeaths(
    state: WorldState,
    balance: BalanceLookup,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    groundItems: GroundItemStore,
    tick: Long,
    rng: Random = Random.Default,
): Pair<WorldState, List<WorldEvent>> {
    val dying = collectDying(state)
    if (dying.isEmpty()) return state to emptyList()

    val xpLoss = balance.xpLossOnDeath()
    val windowTicks = balance.killStreakWindowTicks()
    val events = mutableListOf<WorldEvent>()
    var nextState = state
    var nextPositions = state.positions
    for ((agentId, deathNode) in dying) {
        val outcome = agents.applyDeathPenalty(agentId, xpLoss)
        if (outcome == null) {
            log.warn("death sweep: positioned agent {} missing from registry — clearing position", agentId.id)
            nextPositions = nextPositions - agentId
            continue
        }
        val (stateAfterDrop, dropped) = rollDrop(nextState, agentId, deathNode, balance, windowTicks, equipment, groundItems, tick, rng)
        nextState = stateAfterDrop.updateKillStreak(agentId, AgentKillStreak.EMPTY)
        nextPositions = nextPositions - agentId
        events += buildDeathEvent(agentId, deathNode, outcome, tick, dropped)
        if (dropped != null) {
            events += WorldEvent.ItemDroppedOnGround(
                at = deathNode,
                byAgent = agentId,
                drop = dropped,
                tick = tick,
                // TODO(combat): propagate killing-attack commandId once Phase 2 lands.
                causedBy = null,
            )
        }
    }

    return nextState.copy(positions = nextPositions) to events
}

/**
 * Sorted by agent id for deterministic event fan-out — Phase 2 combat will routinely
 * land multiple deaths per tick. `<= 0` rather than `== 0` accepts overkill damage from
 * combat without revisiting the predicate later (starvation alone only zeroes HP).
 */
private fun collectDying(state: WorldState): List<Pair<AgentId, NodeId>> =
    state.positions.entries
        .mapNotNull { (id, nodeId) ->
            val body = state.bodies[id] ?: return@mapNotNull null
            if (body.hp <= 0) id to nodeId else null
        }
        .sortedBy { it.first.id }

private fun rollDrop(
    state: WorldState,
    agentId: AgentId,
    deathNode: NodeId,
    balance: BalanceLookup,
    windowTicks: Long,
    equipment: EquipmentInstanceStore,
    groundItems: GroundItemStore,
    tick: Long,
    rng: Random,
): Pair<WorldState, DroppedItemView?> {
    val streak = state.killStreakOf(agentId)
    val effectiveKills = streak.effectiveKillCount(tick, windowTicks)
    val dropChance = balance.dropChanceForKillCount(effectiveKills)
    if (dropChance <= 0.0 || rng.nextDouble() >= dropChance) return state to null

    val pool = buildDropPool(state, agentId, equipment)
    if (pool.isEmpty()) return state to null

    val choice = pool[rng.nextInt(pool.size)]
    val drop = choice.toDroppedItemView(UUID.randomUUID())
    groundItems.deposit(deathNode, drop, tick)
    val nextState = applyDropMutation(state, agentId, choice, equipment)
    return nextState to drop
}

private fun buildDropPool(
    state: WorldState,
    agentId: AgentId,
    equipment: EquipmentInstanceStore,
): List<DropPoolEntry> {
    val entries = mutableListOf<DropPoolEntry>()
    state.inventoryOf(agentId).stacks.forEach { (item, quantity) ->
        entries += DropPoolEntry.Stackable(item, quantity)
    }
    equipment.equippedFor(agentId).values.forEach { instance ->
        entries += DropPoolEntry.Equipment(instance)
    }
    return entries
}

/**
 * Removes the chosen drop from its source. Stackable drops zero out the entire
 * stack (one pool entry = one stack); equipment drops delete the instance row
 * since the picker re-INSERTs under the new owner.
 */
private fun applyDropMutation(
    state: WorldState,
    agentId: AgentId,
    choice: DropPoolEntry,
    equipment: EquipmentInstanceStore,
): WorldState = when (choice) {
    is DropPoolEntry.Stackable -> {
        val inv = state.inventoryOf(agentId).remove(choice.item, choice.quantity)
        state.updateInventory(agentId, inv)
    }
    is DropPoolEntry.Equipment -> {
        equipment.delete(choice.instance.instanceId)
        state
    }
}

private sealed interface DropPoolEntry {
    fun toDroppedItemView(dropId: UUID): DroppedItemView

    data class Stackable(val item: ItemId, val quantity: Int) : DropPoolEntry {
        override fun toDroppedItemView(dropId: UUID): DroppedItemView =
            DroppedItemView.Stackable(dropId = dropId, item = item, quantity = quantity)
    }

    data class Equipment(val instance: EquipmentInstance) : DropPoolEntry {
        override fun toDroppedItemView(dropId: UUID): DroppedItemView =
            DroppedItemView.Equipment(
                dropId = dropId,
                item = instance.itemId,
                instanceId = instance.instanceId,
                rarity = instance.rarity,
                durabilityCurrent = instance.durabilityCurrent,
                durabilityMax = instance.durabilityMax,
                creatorAgentId = instance.creatorAgentId?.id,
                createdAtTick = instance.createdAtTick,
            )
    }
}

private fun buildDeathEvent(
    agentId: AgentId,
    deathNode: NodeId,
    outcome: DeathPenaltyOutcome,
    tick: Long,
    droppedItem: DroppedItemView?,
): WorldEvent.AgentDied = WorldEvent.AgentDied(
    agent = agentId,
    at = deathNode,
    xpLost = outcome.xpLost,
    deleveled = outcome.deleveled,
    attributePointLost = outcome.attributePointLost?.let { loss ->
        when (loss) {
            is AttributePointLoss.Unspent -> "UNSPENT"
            is AttributePointLoss.Allocated -> loss.attribute.name
        }
    },
    tick = tick,
    // TODO(combat): route the killing-attack's commandId once Phase 2 lands.
    causedBy = null,
    droppedItem = droppedItem,
)
