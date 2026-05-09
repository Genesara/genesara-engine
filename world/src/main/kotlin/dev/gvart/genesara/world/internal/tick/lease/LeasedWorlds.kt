package dev.gvart.genesara.world.internal.tick.lease

import dev.gvart.genesara.world.WorldId

/**
 * Worlds the local pod currently leases. The tick scheduler reads this
 * per cycle; the only implementation is [LeaseManager].
 */
internal interface LeasedWorlds {
    fun held(): List<WorldId>
}
