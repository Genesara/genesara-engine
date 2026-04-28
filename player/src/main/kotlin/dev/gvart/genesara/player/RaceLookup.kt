package dev.gvart.genesara.player

interface RaceLookup {
    fun byId(id: RaceId): Race?
    fun all(): List<Race>
}
