package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.engine.TickAdvancer
import dev.gvart.genesara.world.internal.tick.lease.KnownWorlds
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Catches the global tick counter up to the highest persisted per-world
 * value at startup so the in-memory [CommandQueue]'s tick keying (which
 * uses `tickClock.currentTick()`) lines up with the per-world counter
 * resumed by [RedisWorldTickCounter] after a restart.
 *
 * Reads every known world (not just the ones this pod will lease) — the
 * highest tick across the deployment is the floor we have to clear.
 */
@Component
internal class TickEngineSeeder(
    private val tickAdvancer: TickAdvancer,
    private val knownWorlds: KnownWorlds,
    private val mirror: WorldTickMirror,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun seed() {
        val maxTick = knownWorlds.all().mapNotNull { mirror.read(it) }.maxOrNull() ?: 0L
        if (maxTick > 0L) {
            tickAdvancer.advanceToAtLeast(maxTick)
            log.info("Seeded global tick counter from world_tick mirror: tick={}", maxTick)
        }
    }
}
