package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.engine.TickAdvancer
import dev.gvart.genesara.world.internal.tick.lease.LeasedWorlds
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Drives the simulation. Each cycle: advance the global counter (so
 * [dev.gvart.genesara.engine.TickClock] keeps moving for MCP tools),
 * iterate the worlds this pod currently leases, INCR each per-world
 * counter, and publish a [WorldTick] for each. Worlds the pod doesn't
 * lease are silently skipped — another pod is responsible for them.
 *
 * `seeder` is constructor-injected only to force its `@PostConstruct` to
 * complete before this bean is wired — see [TickEngineSeeder].
 */
@Component
internal class WorldTickScheduler(
    private val tickAdvancer: TickAdvancer,
    private val leased: LeasedWorlds,
    private val counter: WorldTickCounter,
    private val publisher: ApplicationEventPublisher,
    @Suppress("unused") private val seeder: TickEngineSeeder,
) {

    @Scheduled(fixedRateString = "\${application.tick.interval}")
    fun advanceTick() {
        tickAdvancer.incrementAndGet()
        val now = Instant.now()
        for (worldId in leased.held()) {
            val number = counter.incrementAndGet(worldId)
            publisher.publishEvent(WorldTick(worldId, number, now))
        }
    }
}
