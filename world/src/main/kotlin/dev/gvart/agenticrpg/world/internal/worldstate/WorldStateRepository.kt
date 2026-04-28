package dev.gvart.agenticrpg.world.internal.worldstate

internal interface WorldStateRepository {
    fun load(): WorldState
    fun save(state: WorldState)
}
