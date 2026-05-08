package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.LevelScalingAggregator
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
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.crafting.RarityRoller
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.internal.death.processDeaths
import dev.gvart.genesara.world.internal.passive.applyPassives
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.reduce
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver
import dev.gvart.genesara.world.internal.worldstate.WorldOnlinePresence
import dev.gvart.genesara.world.internal.worldstate.WorldStateRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class WorldTickHandler(
    private val queue: CommandQueue,
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
    private val scaling: LevelScalingAggregator,
    private val passiveAura: PassiveAuraAggregator,
    private val spawnLocationResolver: SpawnLocationResolver,
    private val groundItems: GroundItemStore,
    private val deathProcessor: DeathProcessor,
    private val triggeredPassives: TriggeredPassiveDispatcher,
    private val activePerks: ActivePerkLookup,
    private val perkCooldowns: PerkCooldownStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Processes a single world's tick. The state slice loaded here is filtered
     * to that world's online agents — the headline perf win of #78.
     *
     * Tick order: passives → death sweep → queued commands → save. The death
     * sweep runs before commands so a dying agent's queued actions for this
     * tick land on a `state.positions` that no longer contains them and get
     * rejected with the existing `NotInWorld` rejection — no post-mortem play.
     *
     * `@Transactional` on the whole tick: reducers may mutate external state
     * ([NodeResourceStore.decrement] in particular). A crash mid-tick rolls
     * back both the world-state save and the side-channel mutations together.
     *
     * The command queue is still in-memory and keyed by tick number across
     * all worlds (#82 replaces it with a Redis-per-world queue). To keep
     * single-pod multi-world correct here, [CommandQueue.drainFor] takes the
     * online-agent filter so each world only consumes its own commands and
     * sibling handlers at the same tick aren't starved.
     */
    @EventListener
    @Transactional
    fun onTick(tick: WorldTick) {
        val online = presence.onlineIn(tick.worldId)
        val initial = repository.load(tick.worldId, online)
        val (afterPassives, passivesEvent) = applyPassives(initial, balance, tick.number)
        val (afterDeaths, deathEvents) = processDeaths(afterPassives, deathProcessor, tick.number)

        val commands = queue.drainFor(tick.number, online)
        val (next, commandEvents) = commands.fold(afterDeaths to emptyList<WorldEvent>()) { (state, acc), command ->
            reduce(
                state, command, balance, profiles, items, recipes, resources, skills, agents, equipment,
                safeNodes, safeNodeResolver, buildings, buildingsLookup, buildingsCatalog, chestContents,
                rarityRoller, progression, scaling, passiveAura, spawnLocationResolver, groundItems,
                deathProcessor, triggeredPassives, activePerks, perkCooldowns, tick.number,
            ).fold(
                ifLeft = { rejection ->
                    log.info("Rejected {} at tick {} world {}: {}", command, tick.number, tick.worldId.value, rejection)
                    val rejectionEvent = WorldEvent.CommandRejected(
                        agent = command.agent,
                        kind = rejection::class.simpleName ?: "Unknown",
                        rejection = rejection,
                        tick = tick.number,
                        causedBy = command.commandId,
                    )
                    state to (acc + rejectionEvent)
                },
                ifRight = { (newState, newEvents) -> newState to (acc + newEvents) },
            )
        }

        repository.save(tick.worldId, next)
        passivesEvent?.let(publisher::publishEvent)
        deathEvents.forEach(publisher::publishEvent)
        commandEvents.forEach(publisher::publishEvent)
    }
}
