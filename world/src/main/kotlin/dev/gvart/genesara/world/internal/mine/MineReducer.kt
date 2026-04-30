package dev.gvart.genesara.world.internal.mine

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.springframework.context.ApplicationEventPublisher

/**
 * Reducer for [WorldCommand.MineResource]. Mirrors [reduceGather] but only accepts
 * items whose `gathering-skill` is `MINING` — STONE / ORE / COAL / GEM / SALT plus
 * the regenerating mining items (CLAY / PEAT / SAND). Items outside the MINING
 * skill (including those with no `gathering-skill` at all) are rejected with
 * [WorldRejection.WrongVerbForItem] (`expectedVerb = gather`).
 *
 * Stamina cost, yield, decrement, skill XP/recommendation, and event emission are
 * identical to gather: mining and gathering only differ at the verb-eligibility
 * gate. The same [WorldEvent.ResourceGathered] event is emitted on success — the
 * downstream view doesn't care which verb produced the yield.
 *
 * **Rejection priority:**
 * `NotInWorld` → `UnknownNode` → `UnknownItem` → `WrongVerbForItem` (item is
 * not a MINING-skill resource — caller should use `gather`) →
 * `ResourceNotAvailableHere` (no live deposit at this node) /
 * `NodeResourceDepleted` (the node had a deposit but it's mined out) →
 * `NotEnoughStamina`. Verb gate precedes availability so an agent learns "wrong
 * verb" rather than "wrong place" when both would apply.
 */
internal fun reduceMine(
    state: WorldState,
    command: WorldCommand.MineResource,
    balance: BalanceLookup,
    items: ItemLookup,
    resources: NodeResourceStore,
    skills: AgentSkillsRegistry,
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

    // Verb gate: mine only accepts MINING-skill items. WrongVerbForItem precedes the
    // availability check so an agent learns "wrong verb" rather than "wrong place"
    // when both would apply. Pulling `gatheringSkill` into a local makes the post-gate
    // non-nullness explicit instead of relying on smart-cast across the `either {}`
    // lambda boundary.
    val skillIdString = itemDef.gatheringSkill
    ensure(skillIdString == MINING_SKILL) {
        WorldRejection.WrongVerbForItem(
            agent = command.agent,
            item = command.item,
            expectedVerb = "gather",
        )
    }

    val cell = resources.availability(nodeId, command.item, tick)
    if (cell == null) {
        raise(WorldRejection.ResourceNotAvailableHere(command.agent, nodeId, command.item))
    }
    if (cell.quantity == 0) {
        raise(WorldRejection.NodeResourceDepleted(command.agent, nodeId, command.item))
    }

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    val cost = balance.gatherStaminaCost(command.item)
    ensure(body.stamina >= cost) {
        WorldRejection.NotEnoughStamina(command.agent, cost, body.stamina)
    }

    val quantity = balance.gatherYield(command.item).coerceAtMost(cell.quantity)
    resources.decrement(nodeId, command.item, quantity, tick)

    val skillId = SkillId(skillIdString) // verb gate established == "MINING"
    when (val result = skills.addXpIfSlotted(command.agent, skillId, delta = quantity)) {
        is AddXpResult.Accrued -> result.crossedMilestones.forEach { milestone ->
            publisher.publishEvent(
                WorldEvent.SkillMilestoneReached(
                    agent = command.agent,
                    skill = skillId,
                    milestone = milestone,
                    tick = tick,
                    causedBy = command.commandId,
                ),
            )
        }
        AddXpResult.Unslotted -> {
            skills.maybeRecommend(command.agent, skillId, tick)?.let { newCount ->
                val snapshot = skills.snapshot(command.agent)
                publisher.publishEvent(
                    WorldEvent.SkillRecommended(
                        agent = command.agent,
                        skill = skillId,
                        recommendCount = newCount,
                        slotsFree = snapshot.slotCount - snapshot.slotsFilled,
                        tick = tick,
                        causedBy = command.commandId,
                    ),
                )
            }
        }
    }

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

private const val MINING_SKILL = "MINING"
