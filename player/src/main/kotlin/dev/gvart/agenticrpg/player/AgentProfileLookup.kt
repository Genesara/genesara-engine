package dev.gvart.agenticrpg.player

interface AgentProfileLookup {
    fun find(id: AgentId): AgentProfile?
}