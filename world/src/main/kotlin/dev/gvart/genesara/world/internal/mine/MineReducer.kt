package dev.gvart.genesara.world.internal.mine

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
 * Reducer for [WorldCommand.MineResource]. Mirror of [reduceGather][dev.gvart.genesara.world.internal.gather.reduceGather]
 * restricted to MINING-skill items (STONE / ORE / COAL / GEM / SALT plus the
 * regenerating CLAY / PEAT / SAND). Items outside the MINING skill — including those
 * with no `gathering-skill` at all — are rejected with [WorldRejection.WrongVerbForItem]
 * (`expectedVerb = gather`).
 *
 * Stamina cost, yield, decrement, skill XP/recommendation, and event emission are
 * identical to gather — only the verb-eligibility gate differs. Both verbs emit the
 * same [WorldEvent.ResourceGathered]; downstream views don't care which one produced
 * the yield.
 */
internal fun reduceMine(
    state: WorldState,
    command: WorldCommand.MineResource,
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
    val skillIdString = requireMiningItem(command, itemDef.gatheringSkill)
    val cell = requireAvailableDeposit(command.agent, nodeId, command.item, resources, tick)

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.gatherStaminaCost(command.item)
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }

    val quantity = balance.gatherYield(command.item).coerceAtMost(cell.quantity)

    // Carry-cap gate runs before decrement + XP so a rejected mine leaves no
    // side-effect. Mirrors reduceGather; both add-paths must call enforceCarryCap.
    // Missing registry row → invariant violation (the agent is already positioned).
    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
    val currentGrams = state.inventoryOf(command.agent).totalGrams(items) +
        equippedGrams(equipment.equippedFor(command.agent), items)
    val additionalGrams = quantity * itemDef.weightPerUnit
    enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)

    resources.decrement(nodeId, command.item, quantity, tick)
    accrueMiningXp(command.agent, command.commandId, SkillId(skillIdString), quantity, tick, skills, publisher)

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
 * Pulls `gatheringSkill` into a non-null local — smart-cast doesn't carry across the
 * `either {}` lambda boundary, so a returned value is the cleanest way to expose the
 * gate's post-condition to the caller.
 */
private fun Raise<WorldRejection>.requireMiningItem(
    command: WorldCommand.MineResource,
    gatheringSkill: String?,
): String {
    ensure(gatheringSkill == MINING_SKILL) {
        WorldRejection.WrongVerbForItem(
            agent = command.agent,
            item = command.item,
            expectedVerb = "gather",
        )
    }
    return gatheringSkill
}

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

private fun accrueMiningXp(
    agent: AgentId,
    commandId: UUID,
    skillId: SkillId,
    quantity: Int,
    tick: Long,
    skills: AgentSkillsRegistry,
    publisher: ApplicationEventPublisher,
) {
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
