package dev.gvart.genesara.world.invalidation

/** Cross-pod pub/sub fan-out for cache-invalidation and MCP wakeup signals. */
interface InvalidationBus {
    fun publish(message: InvalidationMessage)

    companion object {
        const val CHANNEL = "genesara:invalidation"
    }
}
