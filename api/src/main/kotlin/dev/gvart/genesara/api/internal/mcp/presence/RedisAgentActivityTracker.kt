package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentLastActiveStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
internal class RedisAgentActivityTracker(
    private val redis: StringRedisTemplate,
    private val store: AgentLastActiveStore,
    private val clock: Clock,
) : AgentActivityTracker, ActivitySnapshotSource {

    override fun touch(agent: AgentId) {
        redis.opsForHash<String, String>().put(KEY, agent.id.toString(), clock.instant().toEpochMilli().toString())
    }

    override fun staleAgents(olderThan: Instant): List<AgentId> {
        val all = redis.opsForHash<String, String>().entries(KEY)
        if (all.isEmpty()) return emptyList()
        val cutoff = olderThan.toEpochMilli()
        return all.entries
            .mapNotNull { (id, millis) ->
                val ts = millis.toLongOrNull() ?: return@mapNotNull null
                if (ts < cutoff) parseAgentId(id) else null
            }
    }

    override fun lastActiveAt(agent: AgentId): Instant? {
        val redisValue = redis.opsForHash<String, String>().get(KEY, agent.id.toString())
        if (redisValue != null) return Instant.ofEpochMilli(redisValue.toLong())
        return store.findLastActive(agent)
    }

    override fun lastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> {
        if (ids.isEmpty()) return emptyMap()
        val keys = ids.map { it.id.toString() }
        val redisValues = redis.opsForHash<String, String>().multiGet(KEY, keys)
        val fromRedis = ids.zip(redisValues)
            .mapNotNull { (id, raw) -> raw?.toLongOrNull()?.let { id to Instant.ofEpochMilli(it) } }
            .toMap()
        val missing = ids.filter { it !in fromRedis.keys }
        if (missing.isEmpty()) return fromRedis
        return fromRedis + store.findLastActiveBatch(missing)
    }

    override fun forget(agent: AgentId) {
        redis.opsForHash<String, String>().delete(KEY, agent.id.toString())
    }

    override fun snapshot(): Map<AgentId, Instant> {
        val all = redis.opsForHash<String, String>().entries(KEY)
        if (all.isEmpty()) return emptyMap()
        return all.entries.mapNotNull { (id, millis) ->
            val ts = millis.toLongOrNull() ?: return@mapNotNull null
            parseAgentId(id)?.let { it to Instant.ofEpochMilli(ts) }
        }.toMap()
    }

    private fun parseAgentId(raw: String): AgentId? = try {
        AgentId(UUID.fromString(raw))
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val KEY = "agents:last_active"
    }
}
