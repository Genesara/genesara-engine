package dev.gvart.genesara.world.body.internal.consume

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.RecipeLearning
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.BodyCommand
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

/**
 * Pure reducer for [BodyCommand.ConsumeItem]. Validates presence + ownership +
 * consumability, decrements the inventory by 1, refills the named gauge (clamped to
 * its max), and emits [BodyEvent.ItemConsumed].
 *
 * **Rejection priority:**
 * `NotInWorld` → `UnknownItem` → `ItemNotConsumable` → `ItemNotInInventory`. Catalog
 * checks before ownership so an agent learns about typos before about scarcity.
 *
 * Skill XP: an item tied to a gathering skill (`Item.harvestSkill`) trains that skill
 * on consume as well as on harvest — fixture `05-lvl10-cap` documents this contract
 * by stocking BERRY with FORAGING pre-slotted so agents can grind XP via either verb.
 *
 * Out of scope for this slice: partial-stack consumption (always 1 unit), poison /
 * negative-amount effects, "already at max" rejection (the refill is just clamped —
 * consuming a berry at full hunger is a small waste, not an error).
 */
fun reduceConsume(
    body: BodySlice,
    core: CoreReadView,
    command: BodyCommand.ConsumeItem,
    items: ItemLookup,
    agents: AgentRegistry,
    progression: SkillProgression,
    characterXp: CharacterXpProgression,
    recipeLearning: RecipeLearning,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    ensure(command.agent in core.positions) { WorldRejection.NotInWorld(command.agent) }

    val item = ensureNotNull(items.byId(command.item)) { WorldRejection.UnknownItem(command.item) }

    val effect = ensureNotNull(item.consumable) { WorldRejection.ItemNotConsumable(command.item) }

    val inventory = body.inventoryOf(command.agent)
    ensure(inventory.quantityOf(command.item) > 0) {
        WorldRejection.ItemNotInInventory(command.agent, command.item)
    }

    val currentBody = body.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")

    val before = currentBody.valueOf(effect.gauge)
    val nextBody = currentBody.refill(effect.gauge, effect.amount)
    val refilled = nextBody.valueOf(effect.gauge) - before
    val nextInventory = inventory.remove(command.item, 1)
    item.harvestSkill?.let { skill ->
        val agentRecord = agents.find(command.agent)
            ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
        progression.accrueXp(command.agent, skill, delta = 1, tick, command.commandId, agentRecord.classId)
    }
    characterXp.grant(command.agent, CharacterXpSource.CONSUME, delta = 1, tick = tick, commandId = command.commandId)
    recipeLearning.learnFromItem(command.agent, command.item, tick)
    val nextSlice = body.copy(
        bodies = body.bodies + (command.agent to nextBody),
        inventories = body.inventories + (command.agent to nextInventory),
    )
    val event = BodyEvent.ItemConsumed(
        agent = command.agent,
        item = command.item,
        gauge = effect.gauge,
        refilled = refilled,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = nextSlice, events = listOf(event))
}
