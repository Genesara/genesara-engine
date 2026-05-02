package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.player.AgentId
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class AgentActivityRegistry(private val clock: Clock) : AgentActivityTracker {

    private val lastSeen = ConcurrentHashMap<AgentId, Instant>()

    override fun touch(agent: AgentId) {
        lastSeen[agent] = clock.instant()
    }

    override fun staleAgents(olderThan: Instant): List<AgentId> =
        lastSeen.entries.filter { it.value.isBefore(olderThan) }.map { it.key }

    override fun lastActiveAt(agent: AgentId): Instant? = lastSeen[agent]

    override fun lastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> =
        ids.mapNotNull { id -> lastSeen[id]?.let { id to it } }.toMap()

    override fun forget(agent: AgentId) {
        lastSeen.remove(agent)
    }
}
