package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId

interface AgentRegistry {
    fun find(id: AgentId): Agent?
    fun findByToken(token: String): Agent?
    fun listForOwner(owner: PlayerId): List<Agent>
}
