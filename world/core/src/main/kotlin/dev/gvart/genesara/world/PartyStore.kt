package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Redis-backed store for the active set of parties. Parties are ephemeral: the store
 * makes no durability claims across engine restarts.
 *
 * Concurrency contract: callers are expected to serialize their own writes via the
 * per-agent command queue. The store performs no row-level locking.
 */
interface PartyStore {
    /** Persist a freshly-formed party. Overwrites any existing row for [Party.partyId]. */
    fun create(party: Party)

    fun find(partyId: PartyId): Party?

    /**
     * Resolve via the `agent:{agentId}:party` index. Returns null when the agent is
     * not in any party.
     */
    fun findByAgent(agentId: AgentId): Party?

    /**
     * Append [member] to [partyId]'s roster. Returns the updated party, or null if
     * [partyId] does not exist (caller should treat this as a void invite).
     */
    fun addMember(partyId: PartyId, member: PartyMember): Party?

    /**
     * Remove [agentId] from [partyId]. Returns the updated party (possibly with a
     * different leader; see [replaceLeader]) or null if the agent was not a member.
     * Does NOT auto-dissolve when size drops to 1 — caller decides whether to call
     * [delete].
     */
    fun removeMember(partyId: PartyId, agentId: AgentId): Party?

    fun replaceLeader(partyId: PartyId, newLeader: AgentId): Party?

    fun delete(partyId: PartyId)

    companion object {
        /** Stub for tests that never queue a party command. */
        val NoOp: PartyStore = object : PartyStore {
            override fun create(party: Party): Unit = error("NoOp PartyStore.create")
            override fun find(partyId: PartyId): Party? = null
            override fun findByAgent(agentId: AgentId): Party? = null
            override fun addMember(partyId: PartyId, member: PartyMember): Party? = null
            override fun removeMember(partyId: PartyId, agentId: AgentId): Party? = null
            override fun replaceLeader(partyId: PartyId, newLeader: AgentId): Party? = null
            override fun delete(partyId: PartyId) = Unit
        }
    }
}
