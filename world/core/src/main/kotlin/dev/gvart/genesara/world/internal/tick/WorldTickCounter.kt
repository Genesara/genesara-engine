package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLD_TICK
import jakarta.annotation.PreDestroy
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

interface WorldTickCounter {
    fun incrementAndGet(worldId: WorldId): Long

    /**
     * Last value emitted for [worldId], or `0` if Redis has no value yet.
     * Used by the submit-side guard in `RedisCommandQueue` so a command
     * can't be queued for a tick that has already been drained.
     */
    fun currentTick(worldId: WorldId): Long

    /**
     * Marks [worldId] as needing a re-seed against the Postgres mirror on
     * the next [incrementAndGet]. Called by the lease manager when this
     * pod acquires (or re-acquires) a world so a Redis flush during the
     * un-leased window can't cause the counter to slip backwards.
     */
    fun onLeaseAcquired(worldId: WorldId)
}

/**
 * Per-world monotonically-increasing tick counter. Authoritative store is
 * Redis (`world:{w}:tick`); the `world_tick` Postgres table mirrors the
 * value for cold-start recovery.
 *
 * The seed-then-INCR step is one atomic Lua call so two pods racing on a
 * cold Redis can't both observe `nil` and emit duplicate tick numbers. The
 * Lua takes the DB-mirrored floor as ARGV and: if the key is unset, sets it
 * to the floor before INCRing; otherwise just INCRs. Either way the
 * returned value strictly increases per world across pods.
 */
@Component
internal class RedisWorldTickCounter(
    private val redis: StringRedisTemplate,
    private val mirror: WorldTickMirror,
    private val mirrorWriter: WorldTickMirrorWriter,
) : WorldTickCounter {

    /**
     * Worlds whose Redis counter this pod has already incremented at least
     * once. Subsequent calls take the hot path (plain `INCR`); the first
     * call goes through the seed-then-INCR Lua so a multi-pod cold start
     * can't double-emit a tick number.
     */
    private val seeded = ConcurrentHashMap.newKeySet<Long>()

    override fun incrementAndGet(worldId: WorldId): Long {
        val next = if (seeded.contains(worldId.value)) {
            redis.opsForValue().increment(redisKey(worldId))
                ?: error("Redis INCR returned null for $worldId")
        } else {
            val dbFloor = mirror.read(worldId) ?: 0L
            val n = redis.execute(INCR_WITH_FLOOR, listOf(redisKey(worldId)), dbFloor.toString())
                ?: error("Redis INCR_WITH_FLOOR returned null for $worldId")
            seeded.add(worldId.value)
            n
        }
        mirrorWriter.submit(worldId, next)
        return next
    }

    override fun currentTick(worldId: WorldId): Long =
        redis.opsForValue().get(redisKey(worldId))?.toLongOrNull() ?: 0L

    override fun onLeaseAcquired(worldId: WorldId) {
        seeded.remove(worldId.value)
    }

    private fun redisKey(worldId: WorldId) = "world:${worldId.value}:tick"

    private companion object {
        /**
         * Atomic "seed-then-INCR" so multi-pod cold start can't double-emit
         * a tick number. KEYS[1] = world tick key, ARGV[1] = DB-mirrored
         * floor. If the key is missing AND the floor is positive, SET it to
         * the floor first; in any case INCR and return.
         */
        private val INCR_WITH_FLOOR = DefaultRedisScript<Long>(
            """
            local cur = redis.call('GET', KEYS[1])
            if not cur then
                local floor = tonumber(ARGV[1])
                if floor and floor > 0 then
                    redis.call('SET', KEYS[1], tostring(floor))
                end
            end
            return redis.call('INCR', KEYS[1])
            """.trimIndent(),
            Long::class.java,
        )
    }
}

internal interface WorldTickMirror {
    fun read(worldId: WorldId): Long?
    fun write(worldId: WorldId, tick: Long)
}

@Component
internal class JooqWorldTickMirror(
    private val dsl: DSLContext,
) : WorldTickMirror {

    override fun read(worldId: WorldId): Long? =
        dsl.select(WORLD_TICK.TICK)
            .from(WORLD_TICK)
            .where(WORLD_TICK.WORLD_ID.eq(worldId.value))
            .fetchOne(WORLD_TICK.TICK)

    override fun write(worldId: WorldId, tick: Long) {
        val now = OffsetDateTime.now()
        dsl.insertInto(WORLD_TICK)
            .set(WORLD_TICK.WORLD_ID, worldId.value)
            .set(WORLD_TICK.TICK, tick)
            .set(WORLD_TICK.UPDATED_AT, now)
            .onConflict(WORLD_TICK.WORLD_ID)
            .doUpdate()
            .set(WORLD_TICK.TICK, tick)
            .set(WORLD_TICK.UPDATED_AT, now)
            .execute()
    }
}

@Component
internal class WorldTickMirrorWriter(
    private val mirror: WorldTickMirror,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "world-tick-mirror").apply { isDaemon = true }
    }

    @Volatile private var shuttingDown = false

    fun submit(worldId: WorldId, tick: Long) {
        if (shuttingDown) return
        try {
            executor.execute {
                try {
                    mirror.write(worldId, tick)
                } catch (t: Throwable) {
                    log.warn("Failed to mirror world {} tick={} to Postgres", worldId.value, tick, t)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Executor shut down between the check above and execute() —
            // the late tick is dropped. The next live pod's incrementAndGet
            // re-seeds Redis from `mirror.read(worldId)`, which is at most
            // one tick behind on a clean shutdown and several behind on a
            // crash; the seed-then-INCR Lua keeps the result monotonic.
        }
    }

    @PreDestroy
    fun shutdown() {
        shuttingDown = true
        val service = executor as? java.util.concurrent.ExecutorService ?: return
        service.shutdown()
        if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("WorldTickMirrorWriter did not drain within 5s on shutdown — pending ticks dropped")
        }
    }
}
