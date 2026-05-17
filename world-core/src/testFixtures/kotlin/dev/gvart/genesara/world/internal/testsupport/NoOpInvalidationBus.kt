package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage

internal object NoOpInvalidationBus : InvalidationBus {
    override fun publish(message: InvalidationMessage) = Unit
}
