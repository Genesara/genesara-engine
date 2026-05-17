package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.internal.tick.lease.LeaseLost
import dev.gvart.genesara.world.internal.tick.lease.LeasedWorlds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Bounded parallel fan-out for per-world ticks. The scheduler hands the
 * cycle to [tickAllLeasedWorlds]; this class spins one coroutine per
 * leased world on [Dispatchers.IO] and joins them per cycle.
 *
 * Each coroutine increments its world's counter and invokes
 * [WorldTickRunner.tickOne] via the Spring proxy on its own IO thread,
 * which is what carries the `@Transactional` boundary — Spring `@Transactional`
 * does not propagate across coroutine context switches, so wrapping the
 * outer fan-out in a transaction wouldn't isolate per-world failures.
 *
 * Per-world failures are caught here so a crash in world A doesn't cancel
 * the fan-out for the rest. [LeaseLost] is downgraded to an INFO log
 * because it's the expected outcome of a missed renewal, not a bug.
 */
@Component
internal class WorldTickFanOut(
    private val leased: LeasedWorlds,
    private val counter: WorldTickCounter,
    private val runner: WorldTickRunner,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun tickAllLeasedWorlds() {
        val worldIds = leased.held()
        if (worldIds.isEmpty()) return
        runBlocking {
            supervisorScope {
                worldIds.map { worldId ->
                    async(Dispatchers.IO) {
                        try {
                            val number = counter.incrementAndGet(worldId)
                            runner.tickOne(worldId, number)
                        } catch (e: LeaseLost) {
                            log.info("Tick aborted: lease lost for world {} at tick {}", e.worldId.value, e.tick)
                        } catch (t: Throwable) {
                            // Cancellation must escape so cooperative shutdown unwinds
                            // the runBlocking scope on SIGTERM instead of being logged
                            // as a tick failure and re-armed next cycle.
                            if (t is CancellationException) throw t
                            log.error("Tick failed for world {}", worldId.value, t)
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
