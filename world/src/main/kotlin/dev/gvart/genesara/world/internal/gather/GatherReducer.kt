package dev.gvart.genesara.world.internal.gather

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
 * Pure-ish reducer for [WorldCommand.GatherResource]. Validates presence + terrain +
 * stamina + per-node availability, applies the cost, increments the agent's inventory,
 * decrements the node's resource cell, and emits [WorldEvent.ResourceGathered].
 *
 * **Side effect note.** Unlike the other reducers, this one mutates external state
 * (`NodeResourceStore.decrement`) rather than only `WorldState`. The caller
 * (`WorldTickHandler`) owns the surrounding transaction; if its tx is rolled back, the
 * decrement rolls back too. Treat this as a controlled side-channel — the alternative
 * (loading per-node resources into `WorldState`) would balloon the state object on
 * every tick for a value that's read sparsely.
 *
 * **Rejection priority:**
 * `NotInWorld` → `UnknownNode` → `UnknownItem` → `ResourceNotAvailableHere` (no live
 * deposit at this node — either no spawn rule or the rule failed at paint time) /
 * `NodeResourceDepleted` (the node had a deposit but it's mined out) →
 * `NotEnoughStamina`. The two availability rejections are disjoint cases of the
 * cell-lookup result, both surfaced before stamina so an agent doesn't burn stamina
 * figuring out the deposit is unreachable.
 *
 * **Skill XP and recommendations.** Items declare a `gathering-skill`. If slotted, the
 * skill accrues XP and milestone events publish via the side-channel publisher; if
 * unslotted, the recommendation flow may publish a `SkillRecommended` event (cooldown
 * + cap gated). Items with no `gathering-skill` skip both paths silently.
 *
 * Out of scope for this slice: carry-weight cap, `Item.maxStack` cap,
 * `WorldEvent.NodeResourceDepleted` event on the gather that takes a deposit to zero
 * (depletion is observable via the next `look_around` and the rejection on the next
 * gather attempt; multi-event-per-reducer refactor lands later).
 */
internal fun reduceGather(
    state: WorldState,
    command: WorldCommand.GatherResource,
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
    // Sanity check that the static node graph knows this id — catches state corruption
    // (a position pointing at an evicted node). The node's terrain isn't used here
    // anymore; the resource store is the source of truth for what's available.
    ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    ensureNotNull(items.byId(command.item)) { WorldRejection.UnknownItem(command.item) }

    // Per-node availability check. The store distinguishes "no row" (this item never
    // spawned at this specific node — terrain rule may or may not exist) from "row at
    // zero" (mined out). We surface the two as different rejections so an agent can
    // tell "wrong place" from "deposit gone" — the strategic responses differ.
    val cell = resources.availability(nodeId, command.item, tick)
    if (cell == null) {
        // Either no spawn rule at all on this terrain, OR the rule fired with chance < 1
        // and this node lost the roll at paint time. Both look the same to the agent:
        // scout a different node (perhaps of a different terrain). The split between
        // "no rule" and "spawn-chance failed" exists in the data model but isn't
        // actionable today — keep it collapsed until/unless the agent UX needs it.
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

    // Skill XP / recommendation. Items declare a `gathering-skill`; if the agent has
    // it slotted, XP accrues and any crossed milestones publish `SkillMilestoneReached`.
    // If the skill is unslotted, we instead fire `SkillRecommended` (capped, cooldown-
    // gated). Both events ride the publisher side-channel so the reducer's single-
    // event return shape stays unchanged.
    val itemDef = items.byId(command.item)!!  // existing UnknownItem check above
    itemDef.gatheringSkill?.let { skillIdString ->
        val skillId = SkillId(skillIdString)
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
    }

    // TODO(carry-weight): reject when total weight would exceed Strength × multiplier.
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
