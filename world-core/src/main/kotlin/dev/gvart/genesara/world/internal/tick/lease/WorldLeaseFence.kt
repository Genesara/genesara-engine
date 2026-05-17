package dev.gvart.genesara.world.internal.tick.lease

import dev.gvart.genesara.world.WorldId

/**
 * The fenced check the per-world tick performs before its DB write.
 * Atomic GET-then-EXPIRE: confirms the pod still holds the lease and
 * renews the TTL in one Redis round trip. Throwing [LeaseLost] aborts
 * the surrounding `@Transactional` boundary so the in-flight tick's
 * Postgres writes roll back.
 */
internal interface WorldLeaseFence {
    fun requireHeldAndRenew(worldId: WorldId, tick: Long)
}

internal class LeaseLost(
    val worldId: WorldId,
    val tick: Long,
) : RuntimeException("Lease lost for world ${worldId.value} at tick $tick — fenced check aborted")
