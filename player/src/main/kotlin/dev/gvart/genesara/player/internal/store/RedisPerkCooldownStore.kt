package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis-backed [PerkCooldownStore].
 *
 * ```
 * Key:   agent:{agentId}:cooldowns
 * Hash:  {perkId}  →  untilTick (string-encoded Long)
 * ```
 */
@Component
internal class RedisPerkCooldownStore(
    private val redis: StringRedisTemplate,
) : PerkCooldownStore {

    private val hash get() = redis.opsForHash<String, String>()

    override fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean {
        val until = readyAtTick(agent, perk) ?: return true
        return tick >= until
    }

    override fun readyAtTick(agent: AgentId, perk: PerkId): Long? =
        hash.get(agentKey(agent), perk.value)?.toLongOrNull()

    override fun arm(agent: AgentId, perk: PerkId, untilTick: Long, currentTick: Long) {
        val duration = untilTick - currentTick
        require(duration <= T_PERSIST_TICKS) {
            "Cooldown duration $duration ticks for perk ${perk.value} exceeds T_persist ($T_PERSIST_TICKS); " +
                "cooldowns this long must write-through to the DB. See shard-readiness-sequence.md non-goals."
        }
        hash.put(agentKey(agent), perk.value, untilTick.toString())
    }

    // TODO(shard-perf): N HGETALL round-trips per call; acceptable at tens of online agents per
    // world. Promote to Lettuce pipelining or a single Lua MULTI when world sizes grow.
    override fun byAgents(agents: Set<AgentId>): Map<AgentId, Map<PerkId, Long>> {
        if (agents.isEmpty()) return emptyMap()
        val result = mutableMapOf<AgentId, Map<PerkId, Long>>()
        for (agent in agents) {
            val entries = hash.entries(agentKey(agent))
            if (entries.isEmpty()) continue
            result[agent] = entries.entries.associate { (k, v) -> PerkId(k) to v.toLong() }
        }
        return result
    }

    // TODO(shard-step-3): prefix with `world:{worldId}:` once #80 introduces per-world Redis
    // routing keys — single-pod multi-world is correct without it, but cross-pod isn't.
    private fun agentKey(agent: AgentId) = "agent:${agent.id}:cooldowns"

    companion object {
        /**
         * Maximum cooldown duration (in ticks) Redis is allowed to back without a DB
         * write-through. Sized at ~1 hour assuming the default `application.tick.interval`
         * of 5s (720 × 5s = 3600s). All cooldowns shipped today (Power Strike: 5 ticks,
         * Bleeder: 10 ticks, …) sit well under this; the guard exists to fire when
         * someone adds a perk that genuinely needs durability and forgets to wire the
         * write-through path.
         */
        const val T_PERSIST_TICKS: Long = 720L
    }
}
