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
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.AgentItemInstancesStore
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
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.RecipeLearning
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RelationshipLookup
import dev.gvart.genesara.world.TradeStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.BodyCommand
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.commands.EconomyCommand
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
import dev.gvart.genesara.world.combat.internal.abilities.reduceUseAbility
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.body.internal.body.reduceRefreshDerivedPools
import dev.gvart.genesara.world.environment.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.environment.internal.buildings.reduceBuild
import dev.gvart.genesara.world.environment.internal.buildings.reduceDeposit
import dev.gvart.genesara.world.environment.internal.buildings.reduceToggleGate
import dev.gvart.genesara.world.environment.internal.buildings.reduceWithdraw
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.combat.internal.combat.reduceAttack
import dev.gvart.genesara.world.body.internal.consume.reduceConsume
import dev.gvart.genesara.world.internal.balance.RarityRoller
import dev.gvart.genesara.world.economy.internal.crafting.reduceCraft
import dev.gvart.genesara.world.economy.internal.cultivation.reduceHarvestCrop
import dev.gvart.genesara.world.economy.internal.cultivation.reducePlantCrop
import dev.gvart.genesara.world.economy.internal.cultivation.reduceTendCrop
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.body.internal.death.reduceRespawn
import dev.gvart.genesara.world.internal.death.reduceSetSafeNode
import dev.gvart.genesara.world.body.internal.drink.reduceDrink
import dev.gvart.genesara.world.economy.internal.extract.reduceExtract
import dev.gvart.genesara.world.economy.internal.harvest.reduceHarvest
import dev.gvart.genesara.world.internal.movement.reduceMove
import dev.gvart.genesara.world.environment.internal.npc.LazyNpcSpawnHook
import dev.gvart.genesara.world.environment.internal.npc.LootRoll
import dev.gvart.genesara.world.environment.internal.npc.NoOpLootRoll
import dev.gvart.genesara.world.environment.internal.npc.reduceAttackNpc
import dev.gvart.genesara.world.environment.internal.mount.reduceTame
import dev.gvart.genesara.world.environment.internal.mount.reduceMountTransport
import dev.gvart.genesara.world.environment.internal.mount.reduceDismountTransport
import dev.gvart.genesara.world.environment.internal.mount.reduceMaintain
import dev.gvart.genesara.world.environment.internal.mount.reduceAttackMount
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.body.internal.pickup.reducePickup
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.say.reduceSay
import dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver
import dev.gvart.genesara.world.internal.spawn.reduceSpawn
import dev.gvart.genesara.world.internal.spawn.reduceUnspawn
import dev.gvart.genesara.world.economy.internal.trade.reduceTradeOffer
import dev.gvart.genesara.world.economy.internal.trade.reduceTradeRespond
import dev.gvart.genesara.world.internal.vision.VisionBlockerCache
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import kotlin.random.Random

fun reduce(
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
    itemInstances: AgentItemInstancesStore,
    safeNodes: AgentSafeNodeGateway,
    safeNodeResolver: SafeNodeResolver,
    buildings: BuildingsStore,
    buildingBars: BuildingBarsStore,
    buildingsLookup: BuildingsLookup,
    buildingsCatalog: BuildingsCatalog,
    gateStates: BuildingGateStateStore,
    chestContents: ChestContentsStore,
    plots: AgentPlotsStore,
    crops: CropLookup,
    tradeStore: TradeStore,
    relationships: RelationshipLookup,
    relationshipsGateway: RelationshipsGateway,
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
    visionBlockers: VisionBlockerCache,
    tickIntervalSeconds: Long,
    tick: Long,
    rng: Random = Random.Default,
    classes: ClassLookup = dev.gvart.genesara.player.NoOpClassLookup,
    npcCatalog: NpcCatalog = dev.gvart.genesara.world.environment.internal.npc.NoOpNpcCatalogDefault,
    lootRoll: LootRoll = NoOpLootRoll,
    lazyNpcSpawn: LazyNpcSpawnHook = LazyNpcSpawnHook.NoOp,
    mountCatalog: MountCatalog = NoOpMountCatalogDefault,
    mounts: MountInstanceStore = NoOpMountInstanceStoreDefault,
    mountDeathCleanup: dev.gvart.genesara.world.environment.internal.mount.MountDeathCleanup,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = when (command) {
    is CoreCommand.SpawnAgent -> reduceSpawn(state.core, state.body, command, profiles, spawnLocationResolver, tick)
        .map { out -> state.copy(core = out.sliceDelta).applyEffects(out.effects) to out.events }
    is CoreCommand.MoveAgent ->
        reduceMove(state.core, state.body, command, balance, buildingsLookup, gateStates, scaling, behaviorTracker, tick, mounts, mountCatalog)
            .map { out ->
                val (applied, spawnEvents) = state.copy(core = out.sliceDelta)
                    .applyEffects(out.effects, lazyNpcSpawn, rng)
                applied to (out.events + spawnEvents)
            }
    is CoreCommand.UnspawnAgent -> reduceUnspawn(state.core, command, tick, mounts)
        .map { out -> state.copy(core = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EconomyCommand.Harvest ->
        reduceHarvest(
            state.body, state.core, command, balance, items, resources, agents, itemInstances,
            progression, characterXp, scaling, triggeredPassives, behaviorTracker, tick,
        ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is BodyCommand.ConsumeItem -> reduceConsume(state.body, state.core, command, items, agents, progression, characterXp, recipeLearning, tick)
        .map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is BodyCommand.Drink -> reduceDrink(state.body, state.core, command, balance, buildingsLookup, tick)
        .map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is CoreCommand.SetSafeNode -> reduceSetSafeNode(state.core, command, safeNodes, tick)
        .map { out -> state.copy(core = out.sliceDelta).applyEffects(out.effects) to out.events }
    is BodyCommand.Respawn -> reduceRespawn(state.core, state.body, command, profiles, safeNodes, safeNodeResolver, tick)
        .map { out -> state.copy(core = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.BuildStructure ->
        reduceBuild(
            state.environment, state.body, state.core, command, buildingsCatalog, skills, buildings, buildingBars,
            safeNodes, plots, gateStates, itemInstances, progression, triggeredPassives, behaviorTracker,
            visionBlockers, tick,
        ).map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.DepositToChest ->
        reduceDeposit(state.environment, state.body, state.core, command, items, buildingsCatalog, buildings, chestContents, tick)
            .map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.WithdrawFromChest ->
        reduceWithdraw(state.environment, state.body, state.core, command, buildings, chestContents, tick)
            .map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EconomyCommand.CraftItem ->
        reduceCraft(
            state.body, state.core, command, balance, items, recipes, knownRecipes, itemInstances, buildingsLookup,
            skills, agents, rarityRoller, progression, scaling, triggeredPassives, behaviorTracker, tick,
        ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is BodyCommand.Pickup ->
        reducePickup(state.body, state.core, command, balance, items, agents, itemInstances, groundItems, tick)
            .map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is CombatCommand.AttackTarget ->
        reduceAttack(
            state.combat, state.body, state.core, state.environment, command, balance, items, agents,
            itemInstances, progression, scaling, passiveAura, equipmentBonuses, deathProcessor,
            triggeredPassives, pendingScales, behaviorTracker, relationshipsGateway, rng, tick,
            classes = classes,
        ).map { out -> state.copy(combat = out.sliceDelta).applyEffects(out.effects) to out.events }
    is CombatCommand.UseAbility ->
        reduceUseAbility(
            state.body, state.core, command, activePerks, perkCooldowns, pendingScales,
            progression, balance, behaviorTracker, tickIntervalSeconds, tick,
        ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is BodyCommand.RefreshDerivedPools -> reduceRefreshDerivedPools(state.body, command, tick)
        .map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is CoreCommand.Say -> reduceSay(state.core, command, balance, tick)
        .map { out -> state.copy(core = out.sliceDelta) to out.events }
    is EconomyCommand.TradeOffer ->
        reduceTradeOffer(
            state.body, state.core, command, balance, items, relationships, tradeStore,
            buildingsLookup, passiveAura, scaling, itemInstances, tick,
        ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EconomyCommand.TradeRespond ->
        reduceTradeRespond(
            state.body, state.core, command, items, tradeStore, triggeredPassives, progression, agents,
            itemInstances, tick,
        ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EconomyCommand.PlantCrop ->
        reducePlantCrop(state.body, state.core, command, crops, plots, agents, skills, progression, behaviorTracker, tick)
            .map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EconomyCommand.TendCrop ->
        reduceTendCrop(state.body, state.core, command, crops, plots, agents, progression, behaviorTracker, tick)
            .map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EconomyCommand.HarvestCrop ->
        reduceHarvestCrop(
            state.body, state.core, command, crops, plots, items, agents, skills, itemInstances, balance,
            progression, characterXp, triggeredPassives, behaviorTracker, rng, tick,
        ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.ToggleGate ->
        reduceToggleGate(state.environment, state.core, command, buildings, gateStates, itemInstances, visionBlockers, tick)
            .map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EconomyCommand.Extract ->
        reduceExtract(
            state.body, state.core, command, balance, items, resources, buildingsLookup, agents, itemInstances,
            progression, characterXp, scaling, triggeredPassives, behaviorTracker, tick,
        ).map { out -> state.copy(body = out.sliceDelta).applyEffects(out.effects) to out.events }
    is CombatCommand.AttackNpc ->
        reduceAttackNpc(
            state.environment, state.body, state.core, command, balance, items, agents, itemInstances,
            progression, scaling, passiveAura, equipmentBonuses, pendingScales, behaviorTracker,
            npcCatalog, lootRoll, classes = classes, rng = rng, tick = tick,
        ).map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.Tame ->
        reduceTame(
            state.environment, state.body, state.core, command, balance, agents, skills,
            scaling, passiveAura, mountCatalog, mounts, progression, behaviorTracker, rng, tick,
        ).map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.MountTransport ->
        reduceMountTransport(state.environment, state.core, command, mounts, tick)
            .map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.DismountTransport ->
        reduceDismountTransport(state.environment, state.core, command, mounts, tick)
            .map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is EnvironmentCommand.Maintain ->
        reduceMaintain(state.environment, state.body, state.core, command, items, mountCatalog, mounts, tick)
            .map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    is CombatCommand.AttackMount ->
        reduceAttackMount(
            state.environment, state.body, state.core, command, balance, items, agents,
            itemInstances, equipmentBonuses, mountCatalog, mounts, mountDeathCleanup, rng, tick,
        ).map { out -> state.copy(environment = out.sliceDelta).applyEffects(out.effects) to out.events }
    else -> error("unhandled WorldCommand subtype ${command::class.qualifiedName}")
}

private object NoOpMountCatalogDefault : MountCatalog {
    override fun byType(type: dev.gvart.genesara.world.MountType): dev.gvart.genesara.world.MountDef? = null
    override fun byTamedFromNpc(npcType: dev.gvart.genesara.world.NpcType): dev.gvart.genesara.world.MountDef? = null
    override fun all(): Collection<dev.gvart.genesara.world.MountDef> = emptyList()
}

private object NoOpMountInstanceStoreDefault : MountInstanceStore {
    override fun insert(mount: dev.gvart.genesara.world.Mount) {}
    override fun findById(mountId: dev.gvart.genesara.world.MountId): dev.gvart.genesara.world.Mount? = null
    override fun byNodes(nodeIds: Collection<dev.gvart.genesara.world.NodeId>): List<dev.gvart.genesara.world.Mount> = emptyList()
    override fun findByRider(agentId: dev.gvart.genesara.player.AgentId): dev.gvart.genesara.world.Mount? = null
    override fun all(): List<dev.gvart.genesara.world.Mount> = emptyList()
    override fun delete(mountId: dev.gvart.genesara.world.MountId): Boolean = false
    override fun update(mount: dev.gvart.genesara.world.Mount): Boolean = false
}
