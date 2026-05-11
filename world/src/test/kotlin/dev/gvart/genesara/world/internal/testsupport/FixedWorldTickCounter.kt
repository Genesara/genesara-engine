package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.tick.WorldTickCounter

internal class FixedWorldTickCounter(private val tick: Long = 0L) : WorldTickCounter {
    override fun incrementAndGet(worldId: WorldId): Long = tick
    override fun currentTick(worldId: WorldId): Long = tick
    override fun onLeaseAcquired(worldId: WorldId) = Unit
}
