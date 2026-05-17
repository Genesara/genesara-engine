package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldId

/**
 * Per-world tick body, invoked once per leased world per cycle by
 * [WorldTickFanOut]. Implemented by [WorldTickHandler] in production;
 * the seam exists so the fan-out's parallelism, error isolation, and
 * counter wiring can be unit-tested without standing up the full
 * reducer dependency graph.
 */
internal fun interface WorldTickRunner {
    fun tickOne(worldId: WorldId, number: Long)
}
