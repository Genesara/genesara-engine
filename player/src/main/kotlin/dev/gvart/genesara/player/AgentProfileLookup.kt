package dev.gvart.genesara.player

interface AgentProfileLookup {
    fun find(id: AgentId): AgentProfile?
}