package dev.gvart.agenticrpg.player

interface AgentProfileRepository {
    fun save(profile: AgentProfile)
}