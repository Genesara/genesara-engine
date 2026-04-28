package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Submits an UnspawnAgent command for any agent whose last tool activity falls outside the
 * configured presence window. Runs at `application.presence.reaper-interval`.
 */
@Component
internal class PresenceReaper(
    private val activity: AgentActivityRegistry,
    private val gateway: WorldCommandGateway,
    private val engine: TickClock,
    private val query: WorldQueryGateway,
    private val props: PresenceProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRateString = "\${application.presence.reaper-interval}")
    fun reap() {
        val cutoff = clock.instant().minus(props.timeout)
        val stale = activity.staleAgents(cutoff)
        if (stale.isEmpty()) return

        val nextTick = engine.currentTick() + 1
        stale.forEach { agent ->
            if (query.activePositionOf(agent) != null) {
                log.info("Auto-unspawning idle agent {} at tick {}", agent, nextTick)
                gateway.submit(WorldCommand.UnspawnAgent(agent), nextTick)
            }
            activity.forget(agent)
        }
    }
}
