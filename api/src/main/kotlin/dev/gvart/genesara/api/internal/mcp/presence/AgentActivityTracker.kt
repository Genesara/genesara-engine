package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.player.AgentId
import java.time.Instant

internal interface AgentActivityTracker {
    fun touch(agent: AgentId)
    fun staleAgents(olderThan: Instant): List<AgentId>
    fun lastActiveAt(agent: AgentId): Instant?
    fun lastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant>
    fun forget(agent: AgentId)
}

internal fun interface ActivitySnapshotSource {
    fun snapshot(): Map<AgentId, Instant>
}
