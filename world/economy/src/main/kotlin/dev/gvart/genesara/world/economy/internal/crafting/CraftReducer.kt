package dev.gvart.genesara.world.economy.internal.crafting

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeUnlockMode
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EconomyCommand
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.internal.balance.RarityRoller
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.inventory.enforceCarryCap
import dev.gvart.genesara.world.internal.inventory.equippedGrams
import dev.gvart.genesara.world.internal.inventory.totalGrams
import dev.gvart.genesara.world.internal.perks.TriggerContext
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Single-step reducer for [EconomyCommand.CraftItem]. Mutates [AgentItemInstancesStore]
 * outside the world-state object; the tick handler's surrounding `@Transactional`
 * keeps the row insert and the world-state save in one transaction so a crash
 * mid-tick rolls back both halves together.
 */
fun reduceCraft(
    body: BodySlice,
    core: CoreReadView,
    command: EconomyCommand.CraftItem,
    balance: BalanceLookup,
    items: ItemLookup,
    recipes: RecipeLookup,
    knownRecipes: AgentKnownRecipesGateway,
    itemInstances: AgentItemInstancesStore,
    buildingsLookup: BuildingsLookup,
    skills: AgentSkillsRegistry,
    agents: AgentRegistry,
    rarityRoller: RarityRoller,
    progression: SkillProgression,
    scaling: LevelScalingAggregator,
    triggeredPassives: TriggeredPassiveDispatcher,
    behaviorTracker: BehaviorTracker,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val nodeId = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    ensureNotNull(core.nodes[nodeId]) { WorldRejection.UnknownNode(nodeId) }

    val recipe = ensureNotNull(recipes.byId(command.recipe)) {
        WorldRejection.UnknownRecipe(command.recipe)
    }

    if (recipe.unlockMode !is RecipeUnlockMode.Open &&
        !knownRecipes.isKnown(command.agent, recipe.id)
    ) {
        raise(WorldRejection.UnknownRecipe(command.recipe))
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

    val agentBody = body.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no body")
    ensure(agentBody.stamina >= recipe.staminaCost) {
        WorldRejection.NotEnoughStamina(command.agent, recipe.staminaCost, agentBody.stamina)
    }

    val inventory = body.inventoryOf(command.agent)
    requireMaterials(command.agent, recipe, inventory)

    val outputItem = ensureNotNull(items.byId(recipe.output.item)) {
        WorldRejection.UnknownItem(recipe.output.item)
    }

    val source = recipe.requiresSource?.let { requiredItem ->
        resolveSource(command, recipe, requiredItem, itemInstances)
    }

    val qualityBonus = scaling.bonusFor(command.agent, ScalingEffect.CRAFT_QUALITY_BONUS)
    val effectiveSkillLevel = (skillLevel * (1.0 + qualityBonus)).toInt().coerceAtLeast(skillLevel)

    val mutation = produceOutput(
        command = command,
        recipe = recipe,
        outputItem = outputItem,
        inventory = inventory,
        skillLevel = effectiveSkillLevel,
        agents = agents,
        itemInstances = itemInstances,
        balance = balance,
        items = items,
        rarityRoller = rarityRoller,
        nodeId = nodeId,
        source = source,
        tick = tick,
    )

    mutation.equipmentToInsert?.let(itemInstances::insert)
    mutation.keyToInsert?.let(itemInstances::insert)

    progression.accrueXp(command.agent, recipe.requiredSkill, delta = 1, tick, command.commandId, agents.find(command.agent)?.classId)
    behaviorTracker.record(command.agent, ActionCategory.CRAFT, tick)

    val nextBody = body.copy(
        bodies = body.bodies + (command.agent to agentBody.spendStamina(recipe.staminaCost)),
        inventories = body.inventories + (command.agent to mutation.nextInventory),
    )
    val triggered = triggeredPassives.dispatch(
        firer = command.agent,
        trigger = TriggeredPassiveTrigger.ON_CRAFT_COMPLETE,
        ctx = TriggerContext.None,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = nextBody, events = listOf(mutation.event) + mutation.extraEvents + triggered)
}

/**
 * Resolve and validate the per-instance `source` for recipes that declare
 * `requiresSource`. Today only GATE_KEY sources are supported (the
 * GATE_KEY_COPY recipe). Future upgrade-style recipes will branch here on
 * other item-ids. The three failure modes (missing source, source not owned,
 * source wrong type) collapse to a single rejection so an agent enumerating
 * UUIDs cannot probe the per-instance stores.
 */
private fun Raise<WorldRejection>.resolveSource(
    command: EconomyCommand.CraftItem,
    recipe: Recipe,
    requiredItem: ItemId,
    itemInstances: AgentItemInstancesStore,
): SourceInstance {
    val sourceId = command.source
        ?: raise(WorldRejection.RecipeRequiresSource(command.agent, recipe.id, requiredItem))
    return when (requiredItem.value) {
        "GATE_KEY" -> {
            val key = itemInstances.findById(sourceId) as? ItemInstance.Key
                ?: raise(WorldRejection.RecipeRequiresSource(command.agent, recipe.id, requiredItem))
            if (key.agentId != command.agent || key.itemId != requiredItem) {
                raise(WorldRejection.RecipeRequiresSource(command.agent, recipe.id, requiredItem))
            }
            SourceInstance.Key(key)
        }
        else -> error(
            "Recipe ${recipe.id} declares requires-source=${requiredItem.value} but no resolver is wired.",
        )
    }
}

private sealed interface SourceInstance {
    data class Key(val key: ItemInstance.Key) : SourceInstance
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
    val event: EconomyEvent.ItemCrafted,
    val equipmentToInsert: ItemInstance.Equipment?,
    val keyToInsert: ItemInstance.Key? = null,
    val extraEvents: List<WorldEvent> = emptyList(),
)

private fun Raise<WorldRejection>.produceOutput(
    command: EconomyCommand.CraftItem,
    recipe: Recipe,
    outputItem: Item,
    inventory: AgentInventory,
    skillLevel: Int,
    agents: AgentRegistry,
    itemInstances: AgentItemInstancesStore,
    balance: BalanceLookup,
    items: ItemLookup,
    rarityRoller: RarityRoller,
    nodeId: NodeId,
    source: SourceInstance?,
    tick: Long,
): CraftMutation {
    val afterInputs = recipe.inputs.entries.fold(inventory) { acc, (item, qty) ->
        acc.remove(item, qty)
    }
    return when (outputItem.category) {
        ItemCategory.EQUIPMENT -> equipmentMutation(
            command, recipe, outputItem, afterInputs, skillLevel,
            agents, itemInstances, balance, items, rarityRoller, nodeId, tick,
        )
        ItemCategory.RESOURCE -> stackableMutation(command, recipe, outputItem, afterInputs, nodeId, tick)
        ItemCategory.KEY -> {
            val sourceKey = (source as? SourceInstance.Key)?.key
                ?: error("KEY-output recipe ${recipe.id} requires a Key source")
            keyMutation(command, recipe, outputItem, afterInputs, sourceKey, nodeId, tick)
        }
        ItemCategory.MOUNT_GEAR -> error(
            "MOUNT_GEAR crafting is not wired through CraftReducer yet — " +
                "recipe ${recipe.id} declares a MOUNT_GEAR output without a craft path. " +
                "TODO(stage-e): route through equipmentMutation or a dedicated mount-gear mutation.",
        )
    }
}

/**
 * Output-branch for recipes that mint a per-instance key bound to the same
 * gate as the source template. The source is NOT consumed — the inputs map
 * already carries the consumed materials (e.g. IRON_INGOT). Emits BOTH the
 * canonical `ItemCrafted` (so craft-listening consumers see it) AND a
 * `GateKeyMinted(byCopy=true)` (so key-tracking consumers correlate).
 */
private fun keyMutation(
    command: EconomyCommand.CraftItem,
    recipe: Recipe,
    outputItem: Item,
    afterInputs: AgentInventory,
    sourceKey: ItemInstance.Key,
    nodeId: NodeId,
    tick: Long,
): CraftMutation {
    val mintedId = java.util.UUID.randomUUID()
    val newKey = ItemInstance.Key(
        instanceId = mintedId,
        agentId = command.agent,
        itemId = outputItem.id,
        gateInstanceId = sourceKey.gateInstanceId,
        createdAtTick = tick,
    )
    val crafted = EconomyEvent.ItemCrafted(
        agent = command.agent,
        at = nodeId,
        recipe = recipe.id,
        output = outputItem.id,
        quantity = 1,
        instanceId = mintedId,
        rarity = null,
        tick = tick,
        causedBy = command.commandId,
    )
    val minted = EnvironmentEvent.GateKeyMinted(
        agent = command.agent,
        keyInstanceId = mintedId,
        gateId = sourceKey.gateInstanceId,
        byCopy = true,
        tick = tick,
        causedBy = command.commandId,
    )
    return CraftMutation(
        nextInventory = afterInputs,
        event = crafted,
        equipmentToInsert = null,
        keyToInsert = newKey,
        extraEvents = listOf(minted),
    )
}

private fun Raise<WorldRejection>.equipmentMutation(
    command: EconomyCommand.CraftItem,
    recipe: Recipe,
    outputItem: Item,
    afterInputs: AgentInventory,
    skillLevel: Int,
    agents: AgentRegistry,
    itemInstances: AgentItemInstancesStore,
    balance: BalanceLookup,
    items: ItemLookup,
    rarityRoller: RarityRoller,
    nodeId: NodeId,
    tick: Long,
): CraftMutation {
    val agentRecord = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} has a position but no registry row")
    val currentGrams = afterInputs.totalGrams(items) +
        equippedGrams(itemInstances.equippedFor(command.agent), items)
    val additionalGrams = outputItem.weightPerUnit * recipe.output.quantity
    enforceCarryCap(command.agent, agentRecord.attributes.strength, currentGrams, additionalGrams, balance)

    val templateDurability = outputItem.maxDurability
        ?: error("Equipment item ${outputItem.id.value} has no max-durability — recipe ${recipe.id} mis-pointed")
    val rolled = rarityRoller.roll(skillLevel = skillLevel, luck = agentRecord.attributes.luck)
    val scaledDurability = (templateDurability * balance.rarityMultiplier(rolled))
        .roundToInt()
        .coerceAtLeast(1)

    val instance = ItemInstance.Equipment(
        instanceId = UUID.randomUUID(),
        agentId = command.agent,
        itemId = outputItem.id,
        rarity = rolled,
        durabilityCurrent = scaledDurability,
        durabilityMax = scaledDurability,
        creatorAgentId = command.agent,
        createdAtTick = tick,
        equippedInSlot = null,
    )

    val event = EconomyEvent.ItemCrafted(
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
    command: EconomyCommand.CraftItem,
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
    val event = EconomyEvent.ItemCrafted(
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
