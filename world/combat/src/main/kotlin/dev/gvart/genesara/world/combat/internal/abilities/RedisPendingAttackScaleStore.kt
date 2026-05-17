package dev.gvart.genesara.world.combat.internal.abilities

import dev.gvart.genesara.player.AgentId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore

/**
 * Redis-backed [PendingAttackScaleStore].
 *
 * ```
 * Key:    agent:{agentId}:pending_scale
 * Value:  multiplierPct (string-encoded Int)
 * TTL:    set per [stage] call
 * ```
 */
@Component
internal class RedisPendingAttackScaleStore(
    private val redis: StringRedisTemplate,
) : PendingAttackScaleStore {

    override fun stage(agent: AgentId, multiplierPct: Int, ttlSeconds: Long) {
        require(ttlSeconds > 0) { "stage() ttlSeconds must be positive (got $ttlSeconds)" }
        redis.opsForValue().set(
            agentKey(agent),
            multiplierPct.toString(),
            Duration.ofSeconds(ttlSeconds),
        )
    }

    override fun consume(agent: AgentId): Int? {
        val raw = redis.execute(CONSUME_SCRIPT, listOf(agentKey(agent))) ?: return null
        return raw.toIntOrNull()
    }

    override fun byAgents(agents: Set<AgentId>): Map<AgentId, Int> {
        if (agents.isEmpty()) return emptyMap()
        // Pair `agents` with `keys` in the same iteration order so MGET's
        // index-aligned response can be threaded back to its agent. `Set`
        // iteration order is consistent within a single call.
        val ordered = agents.toList()
        val values = redis.opsForValue().multiGet(ordered.map { agentKey(it) }) ?: return emptyMap()
        return ordered.asSequence()
            .zip(values.asSequence())
            .mapNotNull { (agent, raw) -> raw?.toIntOrNull()?.let { agent to it } }
            .toMap()
    }

    // TODO(shard-step-3): prefix with `world:{worldId}:` once #80 introduces per-world routing.
    private fun agentKey(agent: AgentId) = "agent:${agent.id}:pending_scale"

    private companion object {
        // Atomic GET+DEL — two attacks queued same-tick can't both consume the staged buff.
        // KEYS[1] = pending-scale key.
        private val CONSUME_SCRIPT = DefaultRedisScript<String>(
            """
            local v = redis.call('GET', KEYS[1])
            if not v then return nil end
            redis.call('DEL', KEYS[1])
            return v
            """.trimIndent(),
            String::class.java,
        )
    }
}
