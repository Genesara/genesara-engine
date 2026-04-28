package dev.gvart.agenticrpg.account

interface PlayerLookup {
    fun find(id: PlayerId): Player?
    fun findByUsername(username: String): Player?
}
