package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Pending invitations. Backed by Redis with a TTL on the invite key — once the TTL
 * fires, the invite is gone and stale entries in the per-agent indexes are pruned
 * lazily on read.
 */
interface PartyInviteStore {
    /** Persist [invite] with the configured TTL. Overwrites any existing entry by id. */
    fun create(invite: PartyInvite, ttlSeconds: Long)

    fun find(inviteId: PartyInviteId): PartyInvite?

    /**
     * All currently-live invites targeting [inviteeId]. Lazily prunes index entries
     * whose underlying invite key has expired.
     */
    fun findByInvitee(inviteeId: AgentId): List<PartyInvite>

    /**
     * All currently-live invites originated by [inviterId]. Lazily prunes index
     * entries whose underlying invite key has expired. Used for cap math at
     * `party_invite` time and for the cancel-sweep when [inviterId] dissolves
     * their party.
     */
    fun findByInviter(inviterId: AgentId): List<PartyInvite>

    fun delete(inviteId: PartyInviteId)

    /** Sweep on leader disband / leader explicit unspawn. */
    fun deleteAllByInviter(inviterId: AgentId): List<PartyInvite>

    companion object {
        val NoOp: PartyInviteStore = object : PartyInviteStore {
            override fun create(invite: PartyInvite, ttlSeconds: Long): Unit = error("NoOp PartyInviteStore.create")
            override fun find(inviteId: PartyInviteId): PartyInvite? = null
            override fun findByInvitee(inviteeId: AgentId): List<PartyInvite> = emptyList()
            override fun findByInviter(inviterId: AgentId): List<PartyInvite> = emptyList()
            override fun delete(inviteId: PartyInviteId) = Unit
            override fun deleteAllByInviter(inviterId: AgentId): List<PartyInvite> = emptyList()
        }
    }
}
