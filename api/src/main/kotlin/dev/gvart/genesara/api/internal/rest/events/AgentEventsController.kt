package dev.gvart.genesara.api.internal.rest.events

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.api.internal.mcp.events.AgentEvent
import dev.gvart.genesara.api.internal.mcp.events.AgentEventLog
import dev.gvart.genesara.api.internal.rest.OwnedAgentResolver
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.UUID

/** Log is TTL-bounded (1h / 500 entries) — a non-contiguous read should be treated as a snapshot. */
@RestController
@RequestMapping("/api/agents/{agentId}/events")
internal class AgentEventsController(
    private val owned: OwnedAgentResolver,
    private val log: AgentEventLog,
    private val broker: AgentEventsBroker,
) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
        @RequestParam(required = false, defaultValue = "0") after: Long,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<AgentEvent> {
        val agent = owned.resolve(player, agentId)
        val cappedLimit = limit.coerceIn(1, MAX_LIMIT)
        return log.since(agent.id, after).take(cappedLimit)
    }

    @GetMapping("/stream", produces = ["text/event-stream"])
    fun stream(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
        @RequestParam(required = false, defaultValue = "0") after: Long,
    ): SseEmitter {
        val agent = owned.resolve(player, agentId)
        return broker.register(agent.id, after, STREAM_TIMEOUT.toMillis())
    }

    private companion object {
        const val MAX_LIMIT = 500
        val STREAM_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
