package dev.gvart.genesara.player

interface PerkCooldownStore {

    fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean

    fun arm(agent: AgentId, perk: PerkId, untilTick: Long)
}
