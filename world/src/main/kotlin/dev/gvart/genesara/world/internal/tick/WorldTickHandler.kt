package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.crafting.RarityRoller
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.internal.death.processDeaths
import dev.gvart.genesara.world.internal.passive.applyPassives
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.reduce
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver
import dev.gvart.genesara.world.internal.tick.lease.WorldLeaseFence
import dev.gvart.genesara.world.internal.worldstate.WorldOnlinePresence
import dev.gvart.genesara.world.internal.worldstate.WorldStateRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Component
internal class WorldTickHandler(
    private val drainer: WorldCommandDrainer,
    private val repository: WorldStateRepository,
    private val presence: WorldOnlinePresence,
    private val publisher: ApplicationEventPublisher,
    private val balance: BalanceLookup,
    private val profiles: AgentProfileLookup,
    private val items: ItemLookup,
    private val recipes: RecipeLookup,
    private val resources: NodeResourceStore,
    private val skills: AgentSkillsRegistry,
    private val agents: AgentRegistry,
    private val equipment: EquipmentInstanceStore,
    private val safeNodes: AgentSafeNodeGateway,
    private val safeNodeResolver: SafeNodeResolver,
    private val buildings: BuildingsStore,
    private val buildingsLookup: BuildingsLookup,
    private val buildingsCatalog: BuildingsCatalog,
    private val chestContents: ChestContentsStore,
    private val rarityRoller: RarityRoller,
    private val progression: SkillProgression,
    private val characterXp: CharacterXpProgression,
    private val scaling: LevelScalingAggregator,
    private val passiveAura: PassiveAuraAggregator,
    private val spawnLocationResolver: SpawnLocationResolver,
    private val groundItems: GroundItemStore,
    private val deathProcessor: DeathProcessor,
    private val triggeredPassives: TriggeredPassiveDispatcher,
    private val activePerks: ActivePerkLookup,
    private val perkCooldowns: PerkCooldownStore,
    private val pendingScales: PendingAttackScaleStore,
    private val behaviorTracker: BehaviorTracker,
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
        val initial = repository.load(worldId, online)
        val (afterPassives, passivesEvent) = applyPassives(initial, balance, number)
        val (afterDeaths, deathEvents) = processDeaths(afterPassives, deathProcessor, number)

        val commands = drainer.drainFor(worldId, number)
        val (next, commandEvents) = commands.fold(afterDeaths to emptyList<WorldEvent>()) { (state, acc), command ->
            reduce(
                state, command, balance, profiles, items, recipes, resources, skills, agents, equipment,
                safeNodes, safeNodeResolver, buildings, buildingsLookup, buildingsCatalog, chestContents,
                rarityRoller, progression, characterXp, scaling, passiveAura, spawnLocationResolver, groundItems,
                deathProcessor, triggeredPassives, activePerks, perkCooldowns, pendingScales,
                behaviorTracker, tickIntervalSeconds, number, classes = classes,
            ).fold(
                ifLeft = { rejection ->
                    log.info("Rejected {} at tick {} world {}: {}", command, number, worldId.value, rejection)
                    val rejectionEvent = WorldEvent.CommandRejected(
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

        leaseFence.requireHeldAndRenew(worldId, number)
        repository.save(worldId, next)
        passivesEvent?.let(publisher::publishEvent)
        deathEvents.forEach(publisher::publishEvent)
        commandEvents.forEach(publisher::publishEvent)
    }
}
