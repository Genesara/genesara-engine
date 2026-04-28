package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId

data class Agent(
    val id: AgentId,
    val owner: PlayerId,
    val name: String,
    val apiToken: String,
    val classId: AgentClass? = null,
)
