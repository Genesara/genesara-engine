package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.engine.Tick
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.internal.death.processDeaths
import dev.gvart.genesara.world.internal.passive.applyPassives
import dev.gvart.genesara.world.internal.reduce
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
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
    private val publisher: ApplicationEventPublisher,
    private val balance: BalanceLookup,
    private val profiles: AgentProfileLookup,
    private val items: ItemLookup,
    private val resources: NodeResourceStore,
    private val skills: AgentSkillsRegistry,
    private val agents: AgentRegistry,
    private val equipment: EquipmentInstanceStore,
    private val safeNodes: AgentSafeNodeGateway,
    private val safeNodeResolver: SafeNodeResolver,
    private val buildings: BuildingsStore,
    private val buildingsCatalog: BuildingsCatalog,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `@Transactional` on the whole tick: reducers may mutate external state
     * ([NodeResourceStore.decrement] in particular). A crash mid-tick rolls back both
     * the world-state save and the side-channel mutations together.
     *
     * Tick order: passives → death sweep → queued commands → save. The death
     * sweep runs before commands so a dying agent's queued actions for this
     * tick land on a `state.positions` that no longer contains them and get
     * rejected with the existing `NotInWorld` rejection — no post-mortem
     * play.
     */
    @EventListener
    @Transactional
    fun onTick(tick: Tick) {
        val initial = repository.load()
        val (afterPassives, passivesEvent) = applyPassives(initial, balance, tick.number)
        val (afterDeaths, deathEvents) = processDeaths(afterPassives, balance, agents, tick.number)

        val commands = queue.drainFor(tick.number)
        val (next, commandEvents) = commands.fold(afterDeaths to emptyList<WorldEvent>()) { (state, acc), command ->
            reduce(
                state, command, balance, profiles, items, resources, skills, agents, equipment,
                safeNodes, safeNodeResolver, buildings, buildingsCatalog, publisher, tick.number,
            ).fold(
                ifLeft = { rejection ->
                    log.info("Rejected {} at tick {}: {}", command, tick.number, rejection)
                    state to acc
                },
                ifRight = { (newState, newEvent) -> newState to (acc + newEvent) },
            )
        }

        repository.save(next)
        passivesEvent?.let(publisher::publishEvent)
        deathEvents.forEach(publisher::publishEvent)
        commandEvents.forEach(publisher::publishEvent)
    }
}