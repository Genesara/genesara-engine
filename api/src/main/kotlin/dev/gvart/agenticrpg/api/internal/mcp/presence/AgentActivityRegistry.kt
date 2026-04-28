package dev.gvart.agenticrpg.api.internal.mcp.presence

import dev.gvart.agenticrpg.player.AgentId
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-agent activity for presence/auto-unspawn. An agent is "active" while it has called
 * any MCP tool within the configured presence window. The presence reaper drives auto-unspawn
 * off this signal.
 */
@Component
internal class AgentActivityRegistry(private val clock: Clock) {

    private val lastSeen = ConcurrentHashMap<AgentId, Instant>()

    fun touch(agent: AgentId) {
        lastSeen[agent] = clock.instant()
    }

    fun staleAgents(olderThan: Instant): List<AgentId> =
        lastSeen.entries.filter { it.value.isBefore(olderThan) }.map { it.key }

    fun forget(agent: AgentId) {
        lastSeen.remove(agent)
    }
}
