package dev.gvart.genesara.world

/**
 * Pending faction invitations (clan → faction join handshake). Backed by Redis with a TTL
 * on the invite key; indexed by target clan so the clan's Archon can find and respond.
 * Production impl is `:world:clan` `RedisFactionInviteStore`.
 */
interface FactionInviteStore {
    fun create(invite: FactionInvite, ttlSeconds: Long)

    fun find(inviteId: FactionInviteId): FactionInvite?

    /** Live invites targeting [targetClanId]; lazily prunes index entries whose key expired. */
    fun findByTargetClan(targetClanId: ClanId): List<FactionInvite>

    fun delete(inviteId: FactionInviteId)

    companion object {
        val NoOp: FactionInviteStore = object : FactionInviteStore {
            override fun create(invite: FactionInvite, ttlSeconds: Long): Unit = error("NoOp FactionInviteStore.create")
            override fun find(inviteId: FactionInviteId): FactionInvite? = null
            override fun findByTargetClan(targetClanId: ClanId): List<FactionInvite> = emptyList()
            override fun delete(inviteId: FactionInviteId) = Unit
        }
    }
}
