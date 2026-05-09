package dev.gvart.genesara.engine

/**
 * Mutating side of the tick clock. Exposed so an external scheduler (e.g.
 * the per-world tick fan-out in `:world`) can advance the global counter
 * once per cycle, while [TickClock] remains a read-only view for tools that
 * shouldn't drive the clock themselves.
 */
interface TickAdvancer {
    fun incrementAndGet(): Long
}
