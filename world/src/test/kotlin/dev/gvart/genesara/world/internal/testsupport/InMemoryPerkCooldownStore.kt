package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkId

/** In-memory test stand-in shared by reducer/integration tests that need a real CD store. */
internal class InMemoryPerkCooldownStore : PerkCooldownStore {

    val armedUntil = mutableMapOf<Pair<AgentId, PerkId>, Long>()

    override fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean {
        val until = armedUntil[agent to perk] ?: return true
        return tick >= until
    }

    override fun arm(agent: AgentId, perk: PerkId, untilTick: Long) {
        armedUntil[agent to perk] = untilTick
    }

    override fun readyAtTick(agent: AgentId, perk: PerkId): Long? = armedUntil[agent to perk]
}
