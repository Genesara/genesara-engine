package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.engine.TickAdvancer
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drives the simulation. Each cycle: advance the global counter (so
 * [dev.gvart.genesara.engine.TickClock] keeps moving for MCP tools), then
 * hand off to [WorldTickFanOut] which ticks every leased world in
 * parallel on `Dispatchers.IO`. Worlds the pod doesn't lease are silently
 * skipped — another pod is responsible for them.
 *
 * The global counter advances unconditionally before the per-world
 * fan-out runs, so MCP `tick_now` keeps moving even when every leased
 * world fails or all leases are lost. Per-world counters are
 * incremented inside the fan-out, only for ticks that actually run.
 *
 * The global counter only feeds the MCP `tick_now` read tool and the
 * `appliesAtTick` *hint* tools pass to [dev.gvart.genesara.world.WorldCommandGateway.submit];
 * the gateway clamps that hint against the per-world tick before queueing
 * so a pod whose global clock lags doesn't queue into an already-drained
 * tick.
 */
@Component
class WorldTickScheduler(
    private val tickAdvancer: TickAdvancer,
    private val fanOut: WorldTickFanOut,
) {

    @Scheduled(fixedRateString = "\${application.tick.interval}")
    fun advanceTick() {
        tickAdvancer.incrementAndGet()
        fanOut.tickAllLeasedWorlds()
    }
}
