package dev.gvart.genesara.world.internal.worldstate

internal interface WorldStateRepository {
    fun load(): WorldState
    fun save(state: WorldState)
}
