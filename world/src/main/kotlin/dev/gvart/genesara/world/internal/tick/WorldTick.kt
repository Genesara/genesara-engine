package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldId
import java.time.Instant

internal data class WorldTick(
    val worldId: WorldId,
    val number: Long,
    val occurredAt: Instant,
)
