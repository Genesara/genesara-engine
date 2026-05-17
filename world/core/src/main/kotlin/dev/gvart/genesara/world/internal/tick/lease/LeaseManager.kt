package dev.gvart.genesara.world.internal.tick.lease

import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.tick.WorldTickCounter
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the set of worlds this pod currently ticks. Discovers unleased
 * worlds via [KnownWorlds] and races every other pod for them with
 * `SET NX EX`; serves the leased subset to the tick scheduler; runs the
 * fenced-write check on behalf of the per-world handler; releases all
 * held leases on shutdown so failover takes one cycle, not the full TTL.
 *
 * `MAX_LEASES_PER_POD` caps the per-pod fan-out. When set lower than the
 * world count, pods naturally distribute load via the randomized pick;
 * unleased worlds at cap remain dormant on this pod and another pod
 * (or this pod after a held lease drops) takes them next cycle.
 */
@Component
class LeaseManager(
    private val store: WorldLeaseStore,
    private val knownWorlds: KnownWorlds,
    @Value("\${application.shard.pod-id}") private val podId: String,
    private val counter: WorldTickCounter,
    @Value("\${application.shard.lease.max-per-pod}") private val maxPerPod: Int,
) : LeasedWorlds, WorldLeaseFence {

    private val log = LoggerFactory.getLogger(javaClass)
    private val held = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var shuttingDown = false

    init {
        require(maxPerPod >= 1) { "application.shard.lease.max-per-pod must be >= 1 (got $maxPerPod)" }
    }

    override fun held(): List<WorldId> =
        held.toList().sorted().map { WorldId(it) }

    @Scheduled(fixedRateString = "\${application.tick.interval}")
    fun discover() {
        if (shuttingDown) return
        val capacity = maxPerPod - held.size
        if (capacity <= 0) return
        val candidates = knownWorlds.all().filter { it.value !in held }
        if (candidates.isEmpty()) return
        for (worldId in candidates.shuffled().take(capacity)) {
            if (store.tryAcquire(worldId, podId)) {
                held.add(worldId.value)
                counter.onLeaseAcquired(worldId)
                log.info("Acquired lease for world {} (pod={})", worldId.value, podId)
            }
        }
    }

    override fun requireHeldAndRenew(worldId: WorldId, tick: Long) {
        if (!store.verifyAndRenew(worldId, podId)) {
            held.remove(worldId.value)
            log.warn("Lost lease for world {} at tick {} — aborting tick", worldId.value, tick)
            throw LeaseLost(worldId, tick)
        }
    }

    @PreDestroy
    fun releaseAll() {
        shuttingDown = true
        if (held.isEmpty()) return
        val snapshot = held.toList()
        held.clear()
        for (worldValue in snapshot) {
            try {
                store.release(WorldId(worldValue), podId)
            } catch (t: Throwable) {
                log.warn("Failed to release lease for world {} on shutdown", worldValue, t)
            }
        }
        log.info("Released {} leases on shutdown (pod={})", snapshot.size, podId)
    }
}
