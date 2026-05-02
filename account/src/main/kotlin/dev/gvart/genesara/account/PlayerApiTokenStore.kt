package dev.gvart.genesara.account

interface PlayerApiTokenStore {
    /** Issue a fresh token for the player, replacing the old one. Returns the new token. */
    fun rotate(playerId: PlayerId): String
}
