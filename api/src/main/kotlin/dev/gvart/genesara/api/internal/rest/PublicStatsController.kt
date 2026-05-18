package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@RestController
@RequestMapping("/api/stats")
internal class PublicStatsController(
    private val tick: TickClock,
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    @Value("\${application.tick.interval}") private val tickInterval: Duration,
) {

    data class PublicStatsResponse(
        val tick: Long,
        val tickIntervalMs: Long,
        val onlineAgents: Long,
        val totalAgents: Long,
    )

    @GetMapping
    fun stats(): PublicStatsResponse = PublicStatsResponse(
        tick = tick.currentTick(),
        tickIntervalMs = tickInterval.toMillis(),
        onlineAgents = world.activeAgentCount(),
        totalAgents = agents.totalCount(),
    )
}
