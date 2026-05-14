package dev.gvart.genesara.world.internal

import arrow.core.Either
import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.AgentKeysStore
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BuildingBarsStore
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.RecipeLearning
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RelationshipLookup
import dev.gvart.genesara.world.TradeStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.body.reduceRefreshDerivedPools
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
import dev.gvart.genesara.world.internal.abilities.reduceUseAbility
import dev.gvart.genesara.world.internal.buildings.reduceBuild
import dev.gvart.genesara.world.internal.buildings.reduceDeposit
import dev.gvart.genesara.world.internal.buildings.reduceToggleGate
import dev.gvart.genesara.world.internal.buildings.reduceWithdraw
import dev.gvart.genesara.world.internal.combat.reduceAttack
import dev.gvart.genesara.world.internal.extract.reduceExtract
import dev.gvart.genesara.world.internal.consume.reduceConsume
import dev.gvart.genesara.world.internal.crafting.RarityRoller
import dev.gvart.genesara.world.internal.crafting.reduceCraft
import dev.gvart.genesara.world.internal.cultivation.reduceHarvestCrop
import dev.gvart.genesara.world.internal.cultivation.reducePlantCrop
import dev.gvart.genesara.world.internal.cultivation.reduceTendCrop
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.internal.death.reduceRespawn
import dev.gvart.genesara.world.internal.death.reduceSetSafeNode
import dev.gvart.genesara.world.internal.drink.reduceDrink
import dev.gvart.genesara.world.internal.harvest.reduceHarvest
import dev.gvart.genesara.world.internal.movement.reduceMove
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.pickup.reducePickup
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.say.reduceSay
import dev.gvart.genesara.world.internal.trade.reduceTradeOffer
import dev.gvart.genesara.world.internal.trade.reduceTradeRespond
import dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver
import dev.gvart.genesara.world.internal.spawn.reduceSpawn
import dev.gvart.genesara.world.internal.spawn.reduceUnspawn
import dev.gvart.genesara.world.internal.worldstate.WorldState
import kotlin.random.Random

internal fun reduce(
    state: WorldState,
    command: WorldCommand,
    balance: BalanceLookup,
    profiles: AgentProfileLookup,
    items: ItemLookup,
    recipes: RecipeLookup,
    knownRecipes: AgentKnownRecipesGateway,
    resources: NodeResourceStore,
    skills: AgentSkillsRegistry,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    safeNodes: AgentSafeNodeGateway,
    safeNodeResolver: SafeNodeResolver,
    buildings: BuildingsStore,
    buildingBars: BuildingBarsStore,
    buildingsLookup: BuildingsLookup,
    buildingsCatalog: BuildingsCatalog,
    gateStates: BuildingGateStateStore,
    agentKeys: AgentKeysStore,
    chestContents: ChestContentsStore,
    plots: AgentPlotsStore,
    crops: CropLookup,
    tradeStore: TradeStore,
    relationships: RelationshipLookup,
    rarityRoller: RarityRoller,
    progression: SkillProgression,
    characterXp: CharacterXpProgression,
    recipeLearning: RecipeLearning,
    scaling: LevelScalingAggregator,
    passiveAura: PassiveAuraAggregator,
    equipmentBonuses: EquipmentBonusAggregator,
    spawnLocationResolver: SpawnLocationResolver,
    groundItems: GroundItemStore,
    deathProcessor: DeathProcessor,
    triggeredPassives: TriggeredPassiveDispatcher,
    activePerks: ActivePerkLookup,
    perkCooldowns: PerkCooldownStore,
    pendingScales: PendingAttackScaleStore,
    behaviorTracker: BehaviorTracker,
    tickIntervalSeconds: Long,
    tick: Long,
    rng: Random = Random.Default,
    classes: ClassLookup = dev.gvart.genesara.player.NoOpClassLookup,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = when (command) {
    is WorldCommand.SpawnAgent -> reduceSpawn(state, command, profiles, spawnLocationResolver, tick)
    is WorldCommand.MoveAgent -> reduceMove(state, command, balance, buildingsLookup, gateStates, scaling, behaviorTracker, tick)
    is WorldCommand.UnspawnAgent -> reduceUnspawn(state, command, tick)
    is WorldCommand.Harvest ->
        reduceHarvest(
            state, command, balance, items, resources, agents, equipment,
            progression, characterXp, scaling, triggeredPassives, behaviorTracker, tick,
        )
    is WorldCommand.ConsumeItem -> reduceConsume(state, command, items, agents, progression, characterXp, recipeLearning, tick)
    is WorldCommand.Drink -> reduceDrink(state, command, balance, buildingsLookup, tick)
    is WorldCommand.SetSafeNode -> reduceSetSafeNode(state, command, safeNodes, tick)
    is WorldCommand.Respawn -> reduceRespawn(state, command, profiles, safeNodes, safeNodeResolver, tick)
    is WorldCommand.BuildStructure ->
        reduceBuild(
            state, command, buildingsCatalog, skills, buildings, buildingBars, safeNodes, plots,
            gateStates, agentKeys, progression, triggeredPassives, behaviorTracker, tick,
        )
    is WorldCommand.DepositToChest ->
        reduceDeposit(state, command, items, buildingsCatalog, buildings, chestContents, tick)
    is WorldCommand.WithdrawFromChest ->
        reduceWithdraw(state, command, buildings, chestContents, tick)
    is WorldCommand.CraftItem ->
        reduceCraft(
            state, command, balance, items, recipes, knownRecipes, equipment, agentKeys, buildingsLookup,
            skills, agents, rarityRoller, progression, scaling, triggeredPassives, behaviorTracker, tick,
        )
    is WorldCommand.Pickup ->
        reducePickup(state, command, balance, items, agents, equipment, groundItems, tick)
    is WorldCommand.AttackTarget ->
        reduceAttack(
            state, command, balance, items, agents, equipment, progression, scaling,
            passiveAura, equipmentBonuses, deathProcessor, triggeredPassives, pendingScales, behaviorTracker, rng, tick,
            classes = classes,
        )
    is WorldCommand.UseAbility ->
        reduceUseAbility(
            state, command, activePerks, perkCooldowns, pendingScales,
            progression, balance, behaviorTracker, tickIntervalSeconds, tick,
        )
    is WorldCommand.RefreshDerivedPools -> reduceRefreshDerivedPools(state, command, tick)
    is WorldCommand.Say -> reduceSay(state, command, balance, tick)
    is WorldCommand.TradeOffer ->
        reduceTradeOffer(state, command, balance, items, relationships, tradeStore, buildingsLookup, passiveAura, scaling, tick)
    is WorldCommand.TradeRespond ->
        reduceTradeRespond(state, command, items, tradeStore, triggeredPassives, progression, agents, tick)
    is WorldCommand.PlantCrop ->
        reducePlantCrop(state, command, crops, plots, agents, skills, progression, behaviorTracker, tick)
    is WorldCommand.TendCrop ->
        reduceTendCrop(state, command, crops, plots, agents, progression, behaviorTracker, tick)
    is WorldCommand.HarvestCrop ->
        reduceHarvestCrop(
            state, command, crops, plots, items, agents, skills, equipment, balance,
            progression, characterXp, triggeredPassives, behaviorTracker, rng, tick,
        )
    is WorldCommand.ToggleGate ->
        reduceToggleGate(state, command, buildings, gateStates, agentKeys, tick)
    is WorldCommand.Extract ->
        reduceExtract(
            state, command, balance, items, resources, buildingsLookup, agents, equipment,
            progression, characterXp, scaling, triggeredPassives, behaviorTracker, tick,
        )
}
