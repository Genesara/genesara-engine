package dev.gvart.genesara.world.internal.tick.lease

import dev.gvart.genesara.world.WorldId
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Per-world lease primitive backing the multi-pod ownership model. Keys
 * follow `world:{worldId}:lease`; the value is the holding pod's id.
 *
 * The contract is intentionally narrow: acquisition is `SET ... NX EX`
 * (built-in atomic), verify-and-renew + compare-and-DEL are short Lua
 * scripts so the read and the write happen in the same Redis round trip.
 * Any caller that ignored the returned booleans would race; the
 * [LeaseManager] is the only intended consumer.
 */
internal interface WorldLeaseStore {
    /** Returns `true` if this pod successfully claimed the lease. */
    fun tryAcquire(worldId: WorldId, podId: String): Boolean

    /**
     * Atomic GET-then-EXPIRE: if the stored value still equals [podId],
     * extends the TTL and returns `true`. Otherwise returns `false`
     * without mutating the key (another pod holds it, or it expired).
     */
    fun verifyAndRenew(worldId: WorldId, podId: String): Boolean

    /**
     * Atomic compare-and-DEL: deletes the key only if its value equals
     * [podId]. Returns `true` when the lease belonged to us and is now
     * gone; `false` when something else held it (no-op).
     */
    fun release(worldId: WorldId, podId: String): Boolean
}

@Component
internal class RedisWorldLeaseStore(
    private val redis: StringRedisTemplate,
    @Value("\${application.shard.lease.ttl:PT10S}") private val ttl: Duration,
    @Value("\${application.tick.interval}") tickInterval: Duration,
) : WorldLeaseStore {

    private val ttlSeconds: String = ttl.toSeconds().toString()

    init {
        require(ttl.toSeconds() >= tickInterval.toSeconds() * 2) {
            "application.shard.lease.ttl ($ttl) must be at least 2 × application.tick.interval ($tickInterval) " +
                "so a single missed renewal can't drop the lease"
        }
    }

    override fun tryAcquire(worldId: WorldId, podId: String): Boolean =
        redis.opsForValue().setIfAbsent(key(worldId), podId, ttl) == true

    override fun verifyAndRenew(worldId: WorldId, podId: String): Boolean =
        redis.execute(VERIFY_AND_RENEW, listOf(key(worldId)), podId, ttlSeconds) == 1L

    override fun release(worldId: WorldId, podId: String): Boolean =
        redis.execute(COMPARE_AND_DEL, listOf(key(worldId)), podId) == 1L

    private fun key(worldId: WorldId) = "world:${worldId.value}:lease"

    private companion object {
        private val VERIFY_AND_RENEW = DefaultRedisScript<Long>(
            """
            local v = redis.call('GET', KEYS[1])
            if v == ARGV[1] then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
                return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        private val COMPARE_AND_DEL = DefaultRedisScript<Long>(
            """
            local v = redis.call('GET', KEYS[1])
            if v == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
