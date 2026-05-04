package dev.gvart.genesara.world.internal.crafting

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.inventory.enforceCarryCap
import dev.gvart.genesara.world.internal.inventory.equippedGrams
import dev.gvart.genesara.world.internal.inventory.totalGrams
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID

/**
 * Single-step reducer for [WorldCommand.CraftItem]. Mutates [EquipmentInstanceStore]
 * outside the world-state object; the tick handler's surrounding `@Transactional`
 * keeps the row insert and the world-state save in one transaction so a crash
 * mid-tick rolls back both halves together.
 */
internal fun reduceCraft(
    state: WorldState,
    command: WorldCommand.CraftItem,
    balance: BalanceLookup,
    items: ItemLookup,
    recipes: RecipeLookup,
    equipment: EquipmentInstanceStore,
    buildingsLookup: BuildingsLookup,
    skills: AgentSkillsRegistry,
    agents: AgentRegistry,
    rarityRoller: RarityRoller,
    progression: SkillProgression,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val nodeId = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    ensureNotNull(state.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val recipe = ensureNotNull(recipes.byId(command.recipe)) {
        WorldRejection.UnknownRecipe(command.recipe)
    }

    ensure(buildingsLookup.activeStationsAt(nodeId, recipe.requiredStation).isNotEmpty()) {
        WorldRejection.RecipeRequiresStation(
            agent = command.agent,
            recipe = recipe.id,
            node = nodeId,
            station = recipe.requiredStation,
        )
    }

    val skillLevel = skills.snapshot(command.agent).perSkill[recipe.requiredSkill]?.level ?: 0
    if (recipe.requiredSkillLevel > 0) {
        ensure(skillLevel >= recipe.requiredSkillLevel) {
            WorldRejection.CraftSkillTooLow(
                agent = command.agent,
                recipe = recipe.id,
                skill = recipe.requiredSkill,
                required = recipe.requiredSkillLevel,
                current = skillLevel,
            )
        }
    }

    val body = state.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    ensure(body.stamina >= recipe.staminaCost) {
        WorldRejection.NotEnoughStamina(command.agent, recipe.staminaCost, body.stamina)
    }

    val inventory = state.inventoryOf(command.agent)
    requireMaterials(command.agent, recipe, inventory)

    val outputItem = ensureNotNull(items.byId(recipe.output.item)) {
        WorldRejection.UnknownItem(recipe.output.item)
    }

    val mutation = produceOutput(
        command = command,
        recipe = recipe,
        outputItem = outputItem,
        inventory = inventory,
        skillLevel = skillLevel,
        agents = agents,
        equipment = equipment,
        balance = balance,
        items = items,
        rarityRoller = rarityRoller,
        nodeId = nodeId,
        tick = tick,
    )

    mutation.equipmentToInsert?.let(equipment::insert)

    progression.accrueXp(command.agent, recipe.requiredSkill, delta = 1, tick, command.commandId)

    val next = state
        .updateBody(command.agent, body.spendStamina(recipe.staminaCost))
        .updateInventory(command.agent, mutation.nextInventory)
    next to mutation.event
}

private fun Raise<WorldRejection>.requireMaterials(
    agent: AgentId,
    recipe: Recipe,
    inventory: AgentInventory,
) {
    for ((item, required) in recipe.inputs) {
        val have = inventory.quantityOf(item)
        if (have < required) {
            raise(
                WorldRejection.InsufficientCraftMaterials(
                    agent = agent,
                    recipe = recipe.id,
                    item = item,
                    required = required,
                    available = have,
                ),
            )
        }
    }
}

private data class CraftMutation(
    val nextInventory: AgentInventory,
    val event: WorldEvent.ItemCrafted,
    val equipmentToInsert: EquipmentInstance?,
)

private fun Raise<WorldRejection>.produceOutput(
    command: WorldCommand.CraftItem,
    recipe: Recipe,
    outputItem: Item,
    inventory: AgentInventory,
    skillLevel: Int,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    balance: BalanceLookup,
    items: ItemLookup,
    rarityRoller: RarityRoller,
    nodeId: NodeId,
    tick: Long,
): CraftMutation {
    val afterInputs = recipe.inputs.entries.fold(inventory) { acc, (item, qty) ->
        acc.remove(item, qty)
    }
    return when (outputItem.category) {
        ItemCategory.EQUIPMENT -> equipmentMutation(
            command, recipe, outputItem, afterInputs, skillLevel,
            agents, equipment, balance, items, rarityRoller, nodeId, tick,
        )
        ItemCategory.RESOURCE -> stackableMutation(command, recipe, outputItem, afterInputs, nodeId, tick)
    }
}

private fun Raise<WorldRejection>.equipmentMutation(
    command: WorldCommand.CraftItem,
    recipe: Recipe,
    outputItem: Item,
    afterInputs: AgentInventory,
    skillLevel: Int,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    balance: BalanceLookup,
    items: ItemLookup,
    rarityRoller: RarityRoller,
    nodeId: NodeId,
    tick: Long,
): CraftMutation {
    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
    val currentGrams = afterInputs.totalGrams(items) +
        equippedGrams(equipment.equippedFor(command.agent), items)
    val additionalGrams = outputItem.weightPerUnit * recipe.output.quantity
    enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)

    val maxDurability = outputItem.maxDurability
        ?: error("Equipment item ${outputItem.id.value} has no max-durability — recipe ${recipe.id} mis-pointed")
    val rolled = rarityRoller.roll(skillLevel = skillLevel, luck = agentRecord.attributes.luck)

    val instance = EquipmentInstance(
        instanceId = UUID.randomUUID(),
        agentId = command.agent,
        itemId = outputItem.id,
        rarity = rolled,
        durabilityCurrent = maxDurability,
        durabilityMax = maxDurability,
        creatorAgentId = command.agent,
        createdAtTick = tick,
        equippedInSlot = null,
    )

    val event = WorldEvent.ItemCrafted(
        agent = command.agent,
        at = nodeId,
        recipe = recipe.id,
        output = outputItem.id,
        quantity = 1,
        instanceId = instance.instanceId,
        rarity = rolled,
        tick = tick,
        causedBy = command.commandId,
    )

    return CraftMutation(afterInputs, event, instance)
}

/**
 * Stackable craft branch — no carry-cap check. The catalog validator enforces
 * `output.weight ≤ Σ inputs.weight` so a craft that consumed materials in
 * inventory cannot push the agent over their cap on the output side. The
 * runtime fence against `maxStack` lives here.
 */
private fun Raise<WorldRejection>.stackableMutation(
    command: WorldCommand.CraftItem,
    recipe: Recipe,
    outputItem: Item,
    afterInputs: AgentInventory,
    nodeId: NodeId,
    tick: Long,
): CraftMutation {
    val current = afterInputs.quantityOf(outputItem.id)
    ensure(current + recipe.output.quantity <= outputItem.maxStack) {
        WorldRejection.StackFull(
            agent = command.agent,
            item = outputItem.id,
            current = current,
            incoming = recipe.output.quantity,
            maxStack = outputItem.maxStack,
        )
    }
    val nextInventory = afterInputs.add(outputItem.id, recipe.output.quantity)
    val event = WorldEvent.ItemCrafted(
        agent = command.agent,
        at = nodeId,
        recipe = recipe.id,
        output = outputItem.id,
        quantity = recipe.output.quantity,
        instanceId = null,
        rarity = null,
        tick = tick,
        causedBy = command.commandId,
    )
    return CraftMutation(nextInventory, event, equipmentToInsert = null)
}

