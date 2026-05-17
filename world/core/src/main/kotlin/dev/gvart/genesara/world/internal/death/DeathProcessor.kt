package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import org.springframework.stereotype.Component

/**
 * Identifies the killing-attack metadata so [DeathProcessor.applyDeath] can
 * propagate the attack's [commandId] to [BodyEvent.AgentDied.causedBy] (and
 * any paired [EconomyEvent.ItemDroppedOnGround]) and increment the attacker's
 * kill streak via [WorldState.incrementKillStreak]. Null cause = starvation
 * death (the post-passive sweep).
 */
data class AttackCause(
    val commandId: UUID,
    val attackerId: AgentId,
)

/**
 * Per-agent death side-effects, factored out of [processDeaths] so the attack
 * reducer can trigger a death inline on a killing blow without duplicating
 * the penalty / drop / position-removal logic. The sweep continues to call
 * [applyDeath] with `cause = null` for starvation; combat passes a non-null
 * [AttackCause] so [BodyEvent.AgentDied.causedBy] carries the killing
 * commandId and the attacker's kill-streak counter ticks up at the same time.
 */
@Component
class DeathProcessor(
    private val balance: BalanceLookup,
    private val agents: AgentRegistry,
    private val equipment: AgentItemInstancesStore,
    private val groundItems: GroundItemStore,
) {
    fun applyDeath(
        state: WorldState,
        agentId: AgentId,
        deathNode: NodeId,
        cause: AttackCause?,
        tick: Long,
        rng: Random,
    ): Pair<WorldState, List<WorldEvent>> {
        val outcome = agents.applyDeathPenalty(agentId, balance.xpLossOnDeath())
            ?: return state.copy(core = state.core.copy(positions = state.core.positions - agentId)) to emptyList()

        val windowTicks = balance.killStreakWindowTicks()
        val (afterDrop, dropped) = rollDrop(state, agentId, deathNode, windowTicks, tick, rng)
        val withStreakReset = afterDrop.updateKillStreak(agentId, AgentKillStreak.EMPTY)
        val withAttackerCredit = if (cause != null) {
            withStreakReset.incrementKillStreak(cause.attackerId, tick, windowTicks)
        } else {
            withStreakReset
        }
        val cleared = withAttackerCredit.copy(
            core = withAttackerCredit.core.copy(positions = withAttackerCredit.core.positions - agentId),
        )

        val events = buildList {
            add(deathEvent(agentId, deathNode, outcome, tick, dropped, cause?.commandId))
            if (dropped != null) {
                add(
                    EconomyEvent.ItemDroppedOnGround(
                        at = deathNode,
                        byAgent = agentId,
                        drop = dropped,
                        tick = tick,
                        causedBy = cause?.commandId,
                    ),
                )
            }
        }

        return cleared to events
    }

    private fun rollDrop(
        state: WorldState,
        agentId: AgentId,
        deathNode: NodeId,
        windowTicks: Long,
        tick: Long,
        rng: Random,
    ): Pair<WorldState, DroppedItemView?> {
        val streak = state.killStreakOf(agentId)
        val effectiveKills = streak.effectiveKillCount(tick, windowTicks)
        val dropChance = balance.dropChanceForKillCount(effectiveKills)
        if (dropChance <= 0.0 || rng.nextDouble() >= dropChance) return state to null

        val pool = buildDropPool(state, agentId)
        if (pool.isEmpty()) return state to null

        val choice = pool[rng.nextInt(pool.size)]
        val drop = choice.toDroppedItemView(UUID.randomUUID())
        groundItems.deposit(deathNode, drop, tick)
        val nextState = applyDropMutation(state, agentId, choice)
        return nextState to drop
    }

    private fun buildDropPool(state: WorldState, agentId: AgentId): List<DropPoolEntry> {
        val entries = mutableListOf<DropPoolEntry>()
        state.inventoryOf(agentId).stacks.forEach { (item, quantity) ->
            entries += DropPoolEntry.Stackable(item, quantity)
        }
        equipment.equippedFor(agentId).values.forEach { instance ->
            entries += DropPoolEntry.Equipment(instance)
        }
        return entries
    }

    private fun applyDropMutation(
        state: WorldState,
        agentId: AgentId,
        choice: DropPoolEntry,
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
}

private fun deathEvent(
    agentId: AgentId,
    deathNode: NodeId,
    outcome: DeathPenaltyOutcome,
    tick: Long,
    droppedItem: DroppedItemView?,
    causedBy: UUID?,
): BodyEvent.AgentDied = BodyEvent.AgentDied(
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
    causedBy = causedBy,
    droppedItem = droppedItem,
)

private sealed interface DropPoolEntry {
    fun toDroppedItemView(dropId: UUID): DroppedItemView

    data class Stackable(val item: ItemId, val quantity: Int) : DropPoolEntry {
        override fun toDroppedItemView(dropId: UUID): DroppedItemView =
            DroppedItemView.Stackable(dropId = dropId, item = item, quantity = quantity)
    }

    data class Equipment(val instance: ItemInstance.Equipment) : DropPoolEntry {
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
