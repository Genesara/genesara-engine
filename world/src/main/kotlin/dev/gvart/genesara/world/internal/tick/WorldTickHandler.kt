package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.engine.Tick
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.passive.applyPassives
import dev.gvart.genesara.world.internal.reduce
import dev.gvart.genesara.world.internal.worldstate.WorldStateRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
internal class WorldTickHandler(
    private val queue: CommandQueue,
    private val repository: WorldStateRepository,
    private val publisher: ApplicationEventPublisher,
    private val balance: BalanceLookup,
    private val profiles: AgentProfileLookup,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onTick(tick: Tick) {
        val initial = repository.load()
        val (afterPassives, passivesEvent) = applyPassives(initial, balance, tick.number)

        val commands = queue.drainFor(tick.number)
        val (next, commandEvents) = commands.fold(afterPassives to emptyList<WorldEvent>()) { (state, acc), command ->
            reduce(state, command, balance, profiles, tick.number).fold(
                ifLeft = { rejection ->
                    log.info("Rejected {} at tick {}: {}", command, tick.number, rejection)
                    state to acc
                },
                ifRight = { (newState, newEvent) -> newState to (acc + newEvent) },
            )
        }

        repository.save(next)
        passivesEvent?.let(publisher::publishEvent)
        commandEvents.forEach(publisher::publishEvent)
    }
}