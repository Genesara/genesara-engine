package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.PerkCooldownStore
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
import dev.gvart.genesara.world.NodeClearedTimestampStore
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcsStore
import dev.gvart.genesara.world.RecipeLearning
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RelationshipLookup
import dev.gvart.genesara.world.TradeStore
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.environment.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.balance.RarityRoller
import dev.gvart.genesara.world.economy.internal.cultivation.CropDecaySweep
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.body.internal.death.processDeaths
import dev.gvart.genesara.world.environment.internal.npc.LazyNpcSpawn
import dev.gvart.genesara.world.environment.internal.npc.LootRoll
import dev.gvart.genesara.world.environment.internal.npc.NpcAiSweep
import dev.gvart.genesara.world.environment.internal.npc.activeNodeSet
import dev.gvart.genesara.world.body.internal.passive.applyPassives
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.reduce
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver
import dev.gvart.genesara.world.internal.tick.lease.WorldLeaseFence
import dev.gvart.genesara.world.internal.worldstate.WorldOnlinePresence
import dev.gvart.genesara.world.internal.worldstate.WorldStateRepository
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class WorldTickHandler(
    private val drainer: WorldCommandDrainer,
    private val repository: WorldStateRepository,
    private val presence: WorldOnlinePresence,
    private val publisher: ApplicationEventPublisher,
    private val balance: BalanceLookup,
    private val profiles: AgentProfileLookup,
    private val profileRepo: AgentProfileRepository,
    private val items: ItemLookup,
    private val recipes: RecipeLookup,
    private val knownRecipes: AgentKnownRecipesGateway,
    private val resources: NodeResourceStore,
    private val skills: AgentSkillsRegistry,
    private val agents: AgentRegistry,
    private val itemInstances: AgentItemInstancesStore,
    private val safeNodes: AgentSafeNodeGateway,
    private val safeNodeResolver: SafeNodeResolver,
    private val buildings: BuildingsStore,
    private val buildingBars: BuildingBarsStore,
    private val buildingsLookup: BuildingsLookup,
    private val buildingsCatalog: BuildingsCatalog,
    private val gateStates: BuildingGateStateStore,
    private val chestContents: ChestContentsStore,
    private val plots: AgentPlotsStore,
    private val crops: CropLookup,
    private val cropDecaySweep: CropDecaySweep,
    private val tradeStore: TradeStore,
    private val relationships: RelationshipLookup,
    private val relationshipsGateway: dev.gvart.genesara.player.RelationshipsGateway,
    private val rarityRoller: RarityRoller,
    private val progression: SkillProgression,
    private val characterXp: CharacterXpProgression,
    private val recipeLearning: RecipeLearning,
    private val scaling: LevelScalingAggregator,
    private val passiveAura: PassiveAuraAggregator,
    private val equipmentBonuses: EquipmentBonusAggregator,
    private val spawnLocationResolver: SpawnLocationResolver,
    private val groundItems: GroundItemStore,
    private val deathProcessor: DeathProcessor,
    private val triggeredPassives: TriggeredPassiveDispatcher,
    private val activePerks: ActivePerkLookup,
    private val perkCooldowns: PerkCooldownStore,
    private val pendingScales: PendingAttackScaleStore,
    private val behaviorTracker: BehaviorTracker,
    private val visionBlockers: dev.gvart.genesara.world.internal.vision.VisionBlockerCache,
    private val partyStore: dev.gvart.genesara.world.PartyStore,
    private val partyInviteStore: dev.gvart.genesara.world.PartyInviteStore,
    private val partyReadView: dev.gvart.genesara.world.internal.worldstate.views.PartyReadView,
    private val visibleNodes: dev.gvart.genesara.world.VisibleNodes,
    private val npcsStore: NpcsStore,
    private val nodeClearedStore: NodeClearedTimestampStore,
    private val npcCatalog: NpcCatalog,
    private val lootRoll: LootRoll,
    private val lazyNpcSpawn: LazyNpcSpawn,
    private val npcAiSweep: NpcAiSweep,
    private val mountCatalog: dev.gvart.genesara.world.MountCatalog = dev.gvart.genesara.world.MountCatalog.NoOp,
    private val mounts: dev.gvart.genesara.world.MountInstanceStore = dev.gvart.genesara.world.MountInstanceStore.NoOp,
    private val mountDeathCleanup: dev.gvart.genesara.world.environment.internal.mount.MountDeathCleanup,
    private val mountMaintenanceSweep: dev.gvart.genesara.world.environment.internal.mount.MountMaintenanceSweep? = null,
    private val outlawDecaySweep: dev.gvart.genesara.world.combat.internal.pvp.OutlawDecaySweep? = null,
    private val leaseFence: WorldLeaseFence,
    @Value("\${application.tick.interval}") private val tickInterval: Duration,
    private val classes: ClassLookup = NoOpClassLookup,
) : WorldTickRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tickIntervalSeconds: Long = tickInterval.toSeconds().also {
        require(it > 0) { "application.tick.interval must be at least 1 second (got ${tickInterval}) — TTLs need a positive seconds value" }
    }

    /**
     * Per-world tick: load the world-state slice (filtered to online
     * agents), run reducers (passives → deaths → commands), fence the
     * lease, save.
     *
     * Death sweep precedes command reduce so a dying agent's queued
     * actions land on a `state.positions` that no longer contains them
     * and surface as the existing `NotInWorld` rejection — no post-mortem
     * play.
     *
     * The fence runs before save. On lease loss, [LeaseLost] propagates
     * out of this `@Transactional` method so any Postgres writes booked
     * by reducers (e.g. [NodeResourceStore.decrement]) roll back together
     * with the save we never reached. Redis-backed reducer side-effects
     * (cooldowns, ground items, pending scales) are *not* part of the
     * rollback and are accepted to leak on lease loss — see the
     * pre-existing-risk note in `docs/shard-readiness-sequence.md`. The
     * window is small because lease loss only fires when a GC pause
     * exceeds the TTL.
     *
     * `@Transactional` lives on this per-world method (not the fan-out
     * caller) because Spring `@Transactional` does not propagate across
     * coroutine context switches — each parallel coroutine in
     * [WorldTickFanOut] hits the proxy on its own `Dispatchers.IO` thread
     * and opens its own transaction, so a failure in world A cannot roll
     * back world B.
     *
     * Commands are drained from a per-world Redis list (`world:{w}:queue:{tick}`)
     * via [WorldCommandDrainer]. A submit-side guard clamps the target tick
     * to `currentTick + 1` so stale tools can't queue into an
     * already-drained tick across pods; routine lease handover is
     * therefore invisible to agents.
     */
    @Transactional
    override fun tickOne(worldId: WorldId, number: Long) {
        val online = presence.onlineIn(worldId)
        val commands = drainer.drainFor(worldId, number)
        // Include pending-command agents so a spawning agent's persisted body
        // is resumed by the reducer instead of overwritten with a fresh one.
        val loadSet = if (commands.isEmpty()) online else online + commands.map { it.agent }
        val initial = repository.load(worldId, loadSet)
        val withNpcs = loadActiveNpcs(initial)
        val (afterPassives, passivesEvent) = applyPassives(withNpcs, balance, number, equipmentBonuses)
        val (afterDeaths, deathEvents) = processDeaths(afterPassives, deathProcessor, number)
        val (afterNpcAi, npcAiEvents) = npcAiSweep.apply(afterDeaths, number)
        val mountSweepEvents = mountMaintenanceSweep?.sweep(number).orEmpty()
        val outlawDecayEvents = outlawDecaySweep?.sweep(number).orEmpty()

        val (next, commandEvents) = commands.fold(afterNpcAi to emptyList<WorldEvent>()) { (state, acc), command ->
            reduce(
                state, command, balance, profiles, items, recipes, knownRecipes, resources, skills, agents, itemInstances,
                safeNodes, safeNodeResolver, buildings, buildingBars, buildingsLookup, buildingsCatalog,
                gateStates, chestContents,
                plots, crops,
                tradeStore, relationships, relationshipsGateway,
                rarityRoller, progression, characterXp, recipeLearning, scaling, passiveAura, equipmentBonuses, spawnLocationResolver, groundItems,
                deathProcessor, triggeredPassives, activePerks, perkCooldowns, pendingScales,
                behaviorTracker, visionBlockers,
                partyStore, partyInviteStore, partyReadView, visibleNodes,
                tickIntervalSeconds, number,
                classes = classes,
                npcCatalog = npcCatalog,
                lootRoll = lootRoll,
                lazyNpcSpawn = lazyNpcSpawn,
                mountCatalog = mountCatalog,
                mounts = mounts,
                mountDeathCleanup = mountDeathCleanup,
                profileRepo = profileRepo,
            ).fold(
                ifLeft = { rejection ->
                    log.info("Rejected {} at tick {} world {}: {}", command, number, worldId.value, rejection)
                    val rejectionEvent = CoreEvent.CommandRejected(
                        agent = command.agent,
                        kind = rejection::class.simpleName ?: "Unknown",
                        rejection = rejection,
                        tick = number,
                        causedBy = command.commandId,
                    )
                    state to (acc + rejectionEvent)
                },
                ifRight = { (newState, newEvents) -> newState to (acc + newEvents) },
            )
        }

        // Sweep AFTER reducers so a same-tick `tend` refreshes its plot before the
        // neglect check runs — an agent who tends precisely on the deadline saves the crop.
        val cropDeathEvents = cropDecaySweep.sweep(number)

        leaseFence.requireHeldAndRenew(worldId, number)
        repository.save(worldId, next)
        flushNpcMutations(next)
        passivesEvent?.let(publisher::publishEvent)
        deathEvents.forEach(publisher::publishEvent)
        npcAiEvents.forEach(publisher::publishEvent)
        commandEvents.forEach(publisher::publishEvent)
        cropDeathEvents.forEach(publisher::publishEvent)
        mountSweepEvents.forEach(publisher::publishEvent)
        outlawDecayEvents.forEach(publisher::publishEvent)
    }

    /**
     * Active-set NPC load (Q4(α)/Q7): every NPC whose node sits within
     * [BalanceLookup.npcSimulationRadius] hops of any online agent. Each
     * loaded NPC's HP is restored to `hpMax` on cold reload (Q14a-iii),
     * matching "out-of-active-set NPCs are dormant; on reload they're full".
     */
    private fun loadActiveNpcs(state: dev.gvart.genesara.world.internal.worldstate.WorldState):
        dev.gvart.genesara.world.internal.worldstate.WorldState {
        if (state.positions.isEmpty()) return state
        val radius = balance.npcSimulationRadius()
        val active = activeNodeSet(state, state.positions.values, radius)
        if (active.isEmpty()) return state
        val rows = npcsStore.byNodes(active)
        if (rows.isEmpty()) return state
        val freshHp = rows.associate { it.id to it.copy(hpCurrent = it.hpMax) }
        return state.copy(environment = state.environment.copy(npcs = freshHp))
    }

    /** Persists NPC mutations + node-cleared timestamps after the per-tick save. */
    private fun flushNpcMutations(state: dev.gvart.genesara.world.internal.worldstate.WorldState) {
        for (id in state.removedNpcs) npcsStore.delete(id)
        for (id in state.dirtyNpcs) {
            val npc = state.npcs[id] ?: continue
            // Insert-or-update: try update first; if the row was just spawned
            // this tick the update no-ops and we fall back to insert.
            val existing = npcsStore.findById(id)
            if (existing == null) npcsStore.insert(npc) else npcsStore.update(npc)
        }
        for ((node, tick) in state.nodesClearedThisTick) {
            nodeClearedStore.setLastClearedTick(node, tick)
        }
    }
}
