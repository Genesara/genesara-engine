package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkId

class InMemoryPerkCooldownStore : PerkCooldownStore {

    val armedUntil = mutableMapOf<Pair<AgentId, PerkId>, Long>()

    override fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean {
        val until = armedUntil[agent to perk] ?: return true
        return tick >= until
    }

    override fun arm(agent: AgentId, perk: PerkId, untilTick: Long, currentTick: Long) {
        armedUntil[agent to perk] = untilTick
    }

    override fun readyAtTick(agent: AgentId, perk: PerkId): Long? = armedUntil[agent to perk]

    override fun byAgents(agents: Set<AgentId>): Map<AgentId, Map<PerkId, Long>> =
        agents.associateWith { agent ->
            armedUntil
                .filterKeys { it.first == agent }
                .mapKeys { (k, _) -> k.second }
        }.filterValues { it.isNotEmpty() }
}
