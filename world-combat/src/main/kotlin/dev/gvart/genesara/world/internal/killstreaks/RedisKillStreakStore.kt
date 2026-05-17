package dev.gvart.genesara.world.internal.killstreaks

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis-backed [KillStreakStore].
 *
 * ```
 * Key:   agent:{agentId}:streak
 * Hash:  count        →  killCount        (Int)
 *        windowStart  →  windowStartTick  (Long)
 * ```
 */
@Component
internal class RedisKillStreakStore(
    private val redis: StringRedisTemplate,
) : KillStreakStore {

    private val hash get() = redis.opsForHash<String, String>()

    // TODO(shard-perf): N HGETALL round-trips per call; acceptable at tens of online agents per
    // world. Promote to Lettuce pipelining or a single Lua MULTI when world sizes grow.
    override fun byAgents(agents: Set<AgentId>): Map<AgentId, AgentKillStreak> {
        if (agents.isEmpty()) return emptyMap()
        val result = mutableMapOf<AgentId, AgentKillStreak>()
        for (agent in agents) {
            val entries = hash.entries(agentKey(agent))
            if (entries.isEmpty()) continue
            val count = entries[FIELD_COUNT]?.toIntOrNull() ?: continue
            val windowStart = entries[FIELD_WINDOW_START]?.toLongOrNull() ?: continue
            result[agent] = AgentKillStreak(killCount = count, windowStartTick = windowStart)
        }
        return result
    }

    override fun save(agent: AgentId, streak: AgentKillStreak) {
        // EMPTY is the absence of a streak; deleting keeps `byAgents` cheap by
        // skipping zero-count rows on the read path.
        if (streak == AgentKillStreak.EMPTY) {
            delete(agent)
            return
        }
        hash.putAll(
            agentKey(agent),
            mapOf(
                FIELD_COUNT to streak.killCount.toString(),
                FIELD_WINDOW_START to streak.windowStartTick.toString(),
            ),
        )
    }

    override fun delete(agent: AgentId) {
        redis.delete(agentKey(agent))
    }

    // TODO(shard-step-3): prefix with `world:{worldId}:` once #80 introduces per-world routing.
    private fun agentKey(agent: AgentId) = "agent:${agent.id}:streak"

    private companion object {
        const val FIELD_COUNT = "count"
        const val FIELD_WINDOW_START = "windowStart"
    }
}
