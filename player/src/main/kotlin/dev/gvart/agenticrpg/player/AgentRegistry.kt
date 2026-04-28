package dev.gvart.agenticrpg.player

import dev.gvart.agenticrpg.account.PlayerId

interface AgentRegistry {
    fun find(id: AgentId): Agent?
    fun findByToken(token: String): Agent?
    fun listForOwner(owner: PlayerId): List<Agent>
}
