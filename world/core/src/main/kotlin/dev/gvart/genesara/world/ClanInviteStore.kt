package dev.gvart.genesara.world

/**
 * Pending clan invitations. Backed by Redis with a TTL on the invite key — once the TTL
 * fires the invite is gone and stale index entries are pruned lazily on read. Indexed by
 * clan (for cap math + the duplicate-invite refresh); the accept path looks the invite up
 * by id. Production impl is `:world:clan` `RedisClanInviteStore`.
 */
interface ClanInviteStore {
    /** Persist [invite] with the configured TTL. Overwrites any existing entry by id. */
    fun create(invite: ClanInvite, ttlSeconds: Long)

    fun find(inviteId: ClanInviteId): ClanInvite?

    /**
     * All currently-live invites issued on behalf of [clanId]. Lazily prunes index entries
     * whose underlying invite key has expired. Used for the clan-cap math at invite time and
     * to detect a duplicate invite to refresh rather than double-count.
     */
    fun findByClan(clanId: ClanId): List<ClanInvite>

    fun delete(inviteId: ClanInviteId)

    companion object {
        val NoOp: ClanInviteStore = object : ClanInviteStore {
            override fun create(invite: ClanInvite, ttlSeconds: Long): Unit = error("NoOp ClanInviteStore.create")
            override fun find(inviteId: ClanInviteId): ClanInvite? = null
            override fun findByClan(clanId: ClanId): List<ClanInvite> = emptyList()
            override fun delete(inviteId: ClanInviteId) = Unit
        }
    }
}
