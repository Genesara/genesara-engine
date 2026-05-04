package dev.gvart.genesara.world.internal

import arrow.core.Either
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.buildings.reduceBuild
import dev.gvart.genesara.world.internal.buildings.reduceDeposit
import dev.gvart.genesara.world.internal.buildings.reduceWithdraw
import dev.gvart.genesara.world.internal.consume.reduceConsume
import dev.gvart.genesara.world.internal.crafting.RarityRoller
import dev.gvart.genesara.world.internal.crafting.reduceCraft
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.internal.death.reduceRespawn
import dev.gvart.genesara.world.internal.death.reduceSetSafeNode
import dev.gvart.genesara.world.internal.drink.reduceDrink
import dev.gvart.genesara.world.internal.harvest.reduceHarvest
import dev.gvart.genesara.world.internal.movement.reduceMove
import dev.gvart.genesara.world.internal.progression.SkillProgression
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver
import dev.gvart.genesara.world.internal.spawn.reduceSpawn
import dev.gvart.genesara.world.internal.spawn.reduceUnspawn
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun reduce(
    state: WorldState,
    command: WorldCommand,
    balance: BalanceLookup,
    profiles: AgentProfileLookup,
    items: ItemLookup,
    recipes: RecipeLookup,
    resources: NodeResourceStore,
    skills: AgentSkillsRegistry,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    safeNodes: AgentSafeNodeGateway,
    safeNodeResolver: SafeNodeResolver,
    buildings: BuildingsStore,
    buildingsLookup: BuildingsLookup,
    buildingsCatalog: BuildingsCatalog,
    chestContents: ChestContentsStore,
    rarityRoller: RarityRoller,
    progression: SkillProgression,
    spawnLocationResolver: SpawnLocationResolver,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, WorldEvent>> = when (command) {
    is WorldCommand.SpawnAgent -> reduceSpawn(state, command, profiles, spawnLocationResolver, tick)
    is WorldCommand.MoveAgent -> reduceMove(state, command, balance, buildingsLookup, tick)
    is WorldCommand.UnspawnAgent -> reduceUnspawn(state, command, tick)
    is WorldCommand.Harvest ->
        reduceHarvest(state, command, balance, items, resources, agents, equipment, progression, tick)
    is WorldCommand.ConsumeItem -> reduceConsume(state, command, items, tick)
    is WorldCommand.Drink -> reduceDrink(state, command, balance, buildingsLookup, tick)
    is WorldCommand.SetSafeNode -> reduceSetSafeNode(state, command, safeNodes, tick)
    is WorldCommand.Respawn -> reduceRespawn(state, command, profiles, safeNodes, safeNodeResolver, tick)
    is WorldCommand.BuildStructure ->
        reduceBuild(state, command, buildingsCatalog, skills, buildings, safeNodes, progression, tick)
    is WorldCommand.DepositToChest ->
        reduceDeposit(state, command, items, buildingsCatalog, buildings, chestContents, tick)
    is WorldCommand.WithdrawFromChest ->
        reduceWithdraw(state, command, buildings, chestContents, tick)
    is WorldCommand.CraftItem ->
        reduceCraft(
            state, command, balance, items, recipes, equipment, buildingsLookup,
            skills, agents, rarityRoller, progression, tick,
        )
}
