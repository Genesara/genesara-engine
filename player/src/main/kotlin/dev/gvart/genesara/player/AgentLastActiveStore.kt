package dev.gvart.genesara.player

import java.time.Instant

interface AgentLastActiveStore {
    fun findLastActive(agentId: AgentId): Instant?
    fun findLastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant>
    fun saveLastActive(updates: Map<AgentId, Instant>)
}
