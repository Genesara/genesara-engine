package dev.gvart.genesara.world.internal.gather

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.inventory.enforceCarryCap
import dev.gvart.genesara.world.internal.inventory.equippedGrams
import dev.gvart.genesara.world.internal.inventory.totalGrams
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Reducer for [WorldCommand.GatherResource]. Validates presence + node + item +
 * verb-gate + availability + stamina, decrements the resource cell, accrues skill XP
 * (or fires a recommendation), updates the agent's inventory, and emits
 * [WorldEvent.ResourceGathered].
 *
 * Unlike sibling reducers this mutates external state ([NodeResourceStore.decrement]).
 * The caller (`WorldTickHandler`) owns the surrounding transaction; if its tx rolls
 * back, the decrement rolls back too. Loading per-node resources into [WorldState]
 * would balloon the state object on every tick for a sparsely-read value.
 *
 * `gather` accepts every item whose `gathering-skill` is *not* `MINING` — including
 * items with no `gathering-skill` at all. The mining verb owns MINING-skill items;
 * the symmetric check lives in [reduceMine][dev.gvart.genesara.world.internal.mine.reduceMine].
 *
 * Out of scope: `Item.maxStack` cap, multi-event return for the gather that takes a
 * cell to zero (see TODOs).
 */
internal fun reduceGather(
    state: WorldState,
    command: WorldCommand.GatherResource,
    balance: BalanceLookup,
    items: ItemLookup,
    resources: NodeResourceStore,
    skills: AgentSkillsRegistry,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    publisher: ApplicationEventPublisher,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val itemDef = ensureNotNull(items.byId(command.item)) {
        WorldRejection.UnknownItem(command.item)
    }
    rejectIfMiningOnlyItem(command, itemDef.gatheringSkill)

    val cell = requireAvailableDeposit(command.agent, nodeId, command.item, resources, tick)

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.gatherStaminaCost(command.item)
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }

    val quantity = balance.gatherYield(command.item).coerceAtMost(cell.quantity)

    // Carry-cap gate runs before the cell decrement and the inventory write so a
    // rejected gather leaves no side-effect (the resource cell stays full, no XP
    // accrues). All add-paths into inventory must call enforceCarryCap; future
    // consume-pickup / craft-output / equip reducers mirror this block. The agent
    // is already known to be in the world (positions[command.agent] above), so a
    // missing registry row is invariant violation, not a rejectable user error.
    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
    val currentGrams = state.inventoryOf(command.agent).totalGrams(items) +
        equippedGrams(equipment.equippedFor(command.agent), items)
    val additionalGrams = quantity * itemDef.weightPerUnit
    enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)

    resources.decrement(nodeId, command.item, quantity, tick)
    accrueXpOrRecommend(command.agent, command.commandId, itemDef.gatheringSkill, quantity, tick, skills, publisher)

    // TODO(max-stack): reject (StackFull) when adding `quantity` would exceed maxStack.
    // TODO(events): emit WorldEvent.NodeResourceDepleted alongside ResourceGathered when
    //               this gather takes the cell to zero — needs multi-event reducer return.
    val nextInventory = state.inventoryOf(command.agent).add(command.item, quantity)
    val next = state
        .updateBody(command.agent, body.spendStamina(cost))
        .updateInventory(command.agent, nextInventory)
    val event = WorldEvent.ResourceGathered(
        agent = command.agent,
        at = nodeId,
        item = command.item,
        quantity = quantity,
        tick = tick,
        causedBy = command.commandId,
    )
    next to event
}

/**
 * Surfaces "wrong verb" before any availability check so the agent gets the actionable
 * error first when both would apply.
 */
private fun Raise<WorldRejection>.rejectIfMiningOnlyItem(
    command: WorldCommand.GatherResource,
    gatheringSkill: String?,
) {
    ensure(gatheringSkill != MINING_SKILL) {
        WorldRejection.WrongVerbForItem(
            agent = command.agent,
            item = command.item,
            expectedVerb = "mine",
        )
    }
}

/**
 * Splits the cell-lookup into two distinct rejections so the agent can tell "wrong place"
 * (no row — no spawn rule on this terrain, or the spawn-chance roll failed at paint time)
 * from "deposit gone" (row at zero — mined out). Strategic responses differ; collapsing
 * the no-rule vs. lost-the-roll distinction is a UX call we accept until it matters.
 */
private fun Raise<WorldRejection>.requireAvailableDeposit(
    agent: AgentId,
    nodeId: NodeId,
    item: ItemId,
    resources: NodeResourceStore,
    tick: Long,
): NodeResourceCell {
    val cell = resources.availability(nodeId, item, tick)
        ?: raise(WorldRejection.ResourceNotAvailableHere(agent, nodeId, item))
    if (cell.quantity == 0) raise(WorldRejection.NodeResourceDepleted(agent, nodeId, item))
    return cell
}

/**
 * Side-channel publish: a slotted skill accrues XP and crossed milestones publish
 * [WorldEvent.SkillMilestoneReached]; an unslotted skill instead fires
 * [WorldEvent.SkillRecommended] (cap + cooldown gated). Items with no `gathering-skill`
 * skip both paths silently. Routed through the publisher so the reducer's single-event
 * return shape stays unchanged.
 */
private fun accrueXpOrRecommend(
    agent: AgentId,
    commandId: UUID,
    gatheringSkill: String?,
    quantity: Int,
    tick: Long,
    skills: AgentSkillsRegistry,
    publisher: ApplicationEventPublisher,
) {
    val skillIdString = gatheringSkill ?: return
    val skillId = SkillId(skillIdString)
    when (val result = skills.addXpIfSlotted(agent, skillId, delta = quantity)) {
        is AddXpResult.Accrued -> result.crossedMilestones.forEach { milestone ->
            publisher.publishEvent(
                WorldEvent.SkillMilestoneReached(
                    agent = agent,
                    skill = skillId,
                    milestone = milestone,
                    tick = tick,
                    causedBy = commandId,
                ),
            )
        }
        AddXpResult.Unslotted -> skills.maybeRecommend(agent, skillId, tick)?.let { newCount ->
            val snapshot = skills.snapshot(agent)
            publisher.publishEvent(
                WorldEvent.SkillRecommended(
                    agent = agent,
                    skill = skillId,
                    recommendCount = newCount,
                    slotsFree = snapshot.slotCount - snapshot.slotsFilled,
                    tick = tick,
                    causedBy = commandId,
                ),
            )
        }
    }
}

private const val MINING_SKILL = "MINING"
