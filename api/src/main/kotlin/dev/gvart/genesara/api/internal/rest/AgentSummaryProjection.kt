package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.NodeId
import java.time.Instant
import java.util.UUID

internal data class AgentSummary(
    val agentId: UUID,
    val name: String,
    val classId: String?,
    val race: String,
    val level: Int,
    val xpCurrent: Int,
    val xpToNext: Int,
    val gauges: Gauges?,
    val locationNodeId: Long?,
    val spawned: Boolean,
    val lastActiveAt: Instant?,
) {

    data class Gauges(
        val hp: Pool,
        val stamina: Pool,
        val mana: Pool,
        val hunger: Pool,
        val thirst: Pool,
        val sleep: Pool,
    )

    data class Pool(val current: Int, val max: Int)
}

internal fun projectAgentSummary(
    agent: Agent,
    body: BodyView?,
    location: NodeId?,
    activeNode: NodeId?,
    lastActiveAt: Instant?,
): AgentSummary = AgentSummary(
    agentId = agent.id.id,
    name = agent.name,
    classId = agent.classId?.name,
    race = agent.race.value,
    level = agent.level,
    xpCurrent = agent.xpCurrent,
    xpToNext = agent.xpToNext,
    gauges = body?.let {
        AgentSummary.Gauges(
            hp = AgentSummary.Pool(it.hp, it.maxHp),
            stamina = AgentSummary.Pool(it.stamina, it.maxStamina),
            mana = AgentSummary.Pool(it.mana, it.maxMana),
            hunger = AgentSummary.Pool(it.hunger, it.maxHunger),
            thirst = AgentSummary.Pool(it.thirst, it.maxThirst),
            sleep = AgentSummary.Pool(it.sleep, it.maxSleep),
        )
    },
    locationNodeId = location?.value,
    spawned = activeNode != null,
    lastActiveAt = lastActiveAt,
)
