package dev.gvart.agenticrpg.player

import dev.gvart.agenticrpg.account.PlayerId

data class Agent(
    val id: AgentId,
    val owner: PlayerId,
    val name: String,
    val apiToken: String,
    val classId: AgentClass? = null,
)
