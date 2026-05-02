package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId

data class Agent(
    val id: AgentId,
    val owner: PlayerId,
    val name: String,
    val classId: AgentClass? = null,
    val race: RaceId = RaceId("human_commoner"),
    val level: Int = 1,
    val xpCurrent: Int = 0,
    val xpToNext: Int = 100,
    val unspentAttributePoints: Int = 5,
    val attributes: AgentAttributes = AgentAttributes.DEFAULT,
)
