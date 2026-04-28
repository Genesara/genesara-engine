package dev.gvart.agenticrpg.player

data class AgentProfile(
    val id: AgentId,
    val maxHp: Int,
    val maxStamina: Int,
    val maxMana: Int,
)