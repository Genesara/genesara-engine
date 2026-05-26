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
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
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
    private val mounts: MountInstanceStore = MountInstanceStore.NoOp,
) {
    fun applyDeath(
        state: WorldState,
        agentId: AgentId,
        deathNode: NodeId,
        cause: AttackCause?,
        tick: Long,
        rng: Random,
    ): Pair<WorldState, List<WorldEvent>> {
        val outcome = computeDeath(
            victimKillStreak = state.killStreakOf(agentId),
            victimInventory = state.inventoryOf(agentId),
            agentId = agentId,
            deathNode = deathNode,
            cause = cause,
            tick = tick,
            rng = rng,
        )
        if (outcome == null) {
            return state.copy(core = state.core.copy(positions = state.core.positions - agentId)) to emptyList()
        }
        // Mirror of the auto-dismount in reduceUnspawn — a dying rider releases its mount.
        // Held to the success branch so a registry-corruption null outcome doesn't write
        // to the mount store while we're skipping the rest of the death cascade.
        mounts.findByRider(agentId)?.let { mount ->
            mounts.update(mount.copy(mountedByAgentId = null))
        }
        return state.applyEffects(outcome.effects) to outcome.events
    }

    /**
     * Pure-ish death cascade for the slice-shaped attack reducer (ADR 0003 §P4).
     * External side-effects on [GroundItemStore] / [AgentItemInstancesStore] still
     * fire (those are not slice writes), but slice mutations are returned as a
     * [DeathOutcome] of [CrossZoneEffect]s + [WorldEvent]s for the caller to thread
     * through `applyEffects`.
     *
     * Returns null when [AgentRegistry.applyDeathPenalty] returns null (the agent
     * is missing from the registry — eager-apply callers fall back to clearing the
     * position with no events).
     */
    fun computeDeath(
        victimKillStreak: AgentKillStreak,
        victimInventory: AgentInventory,
        agentId: AgentId,
        deathNode: NodeId,
        cause: AttackCause?,
        tick: Long,
        rng: Random,
    ): DeathOutcome? {
        val outcome = agents.applyDeathPenalty(agentId, balance.xpLossOnDeath()) ?: return null

        val windowTicks = balance.killStreakWindowTicks()
        val (dropped, inventoryAfterDrop) = rollDrop(
            victimKillStreak, victimInventory, agentId, deathNode, windowTicks, tick, rng,
        )

        val effects = buildList {
            if (inventoryAfterDrop != null) add(CrossZoneEffect.UpdateInventory(agentId, inventoryAfterDrop))
            add(CrossZoneEffect.UpdateKillStreak(agentId, AgentKillStreak.EMPTY))
            if (cause != null) {
                add(CrossZoneEffect.IncrementKillStreak(cause.attackerId, tick, windowTicks))
            }
            add(CrossZoneEffect.RemovePosition(agentId))
        }

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

        return DeathOutcome(effects = effects, events = events)
    }

    private fun rollDrop(
        victimKillStreak: AgentKillStreak,
        victimInventory: AgentInventory,
        agentId: AgentId,
        deathNode: NodeId,
        windowTicks: Long,
        tick: Long,
        rng: Random,
    ): Pair<DroppedItemView?, AgentInventory?> {
        val effectiveKills = victimKillStreak.effectiveKillCount(tick, windowTicks)
        val dropChance = balance.dropChanceForKillCount(effectiveKills)
        if (dropChance <= 0.0 || rng.nextDouble() >= dropChance) return null to null

        val pool = buildDropPool(victimInventory, agentId)
        if (pool.isEmpty()) return null to null

        val choice = pool[rng.nextInt(pool.size)]
        val drop = choice.toDroppedItemView(UUID.randomUUID())
        groundItems.deposit(deathNode, drop, tick)
        val inventoryAfter = when (choice) {
            is DropPoolEntry.Stackable -> victimInventory.remove(choice.item, choice.quantity)
            is DropPoolEntry.Equipment -> {
                equipment.delete(choice.instance.instanceId)
                null
            }
        }
        return drop to inventoryAfter
    }

    private fun buildDropPool(victimInventory: AgentInventory, agentId: AgentId): List<DropPoolEntry> {
        val entries = mutableListOf<DropPoolEntry>()
        victimInventory.stacks.forEach { (item, quantity) ->
            entries += DropPoolEntry.Stackable(item, quantity)
        }
        equipment.equippedFor(agentId).values.forEach { instance ->
            entries += DropPoolEntry.Equipment(instance)
        }
        return entries
    }
}

/**
 * Slice writes + events produced by a single death (ADR 0003 §P4). Returned by
 * [DeathProcessor.computeDeath] so the slice-shaped reducer can thread the
 * effects through `applyEffects` rather than have the cascade mutate
 * [WorldState] in one shot.
 */
data class DeathOutcome(
    val effects: List<CrossZoneEffect>,
    val events: List<WorldEvent>,
)

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
            is AttributePointLoss.NoLossAtFloor -> "AT_FLOOR"
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
