package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Persistent store for clans + membership (mechanics-reference §18). Authoritative
 * for `(clanId, clanRank, factionRank)`; `:player.agents` mirrors only `faction_rank`
 * for the skill-slot formula. Production impl is `:world:clan` `JooqClanRegistry`.
 *
 * Null returns signal "not found" (no exception), matching [AgentRegistry]'s convention.
 */
interface ClanRegistry {

    /**
     * Atomically create a clan named [name] with [founder] enrolled as [ClanRank.ARCHON].
     * Rejects with [CreateClanOutcome.NameTaken] on a name collision and
     * [CreateClanOutcome.AlreadyInClan] when the founder already belongs to a clan
     * (one-clan-per-agent).
     */
    fun createClan(name: String, founder: AgentId, tick: Long): CreateClanOutcome

    fun findClan(clanId: ClanId): Clan?

    /** The agent's membership, or null when the agent is in no clan. */
    fun clanOf(agentId: AgentId): ClanMembership?

    /** Members ordered by `joinedAtTick` ascending. Empty when the clan is missing. */
    fun roster(clanId: ClanId): List<ClanMember>

    fun memberCount(clanId: ClanId): Int

    /**
     * Enrol [agentId] into [clanId] at [rank]. Rejects [AddMemberOutcome.AlreadyInClan]
     * when the agent already belongs to a clan and [AddMemberOutcome.ClanNotFound] when
     * the clan is gone.
     */
    fun addMember(clanId: ClanId, agentId: AgentId, rank: ClanRank, tick: Long): AddMemberOutcome

    /** Set [agentId]'s clan rank within [clanId]. Returns false when the membership is missing. */
    fun changeClanRank(clanId: ClanId, agentId: AgentId, newRank: ClanRank): Boolean

    /** Remove [agentId] from [clanId]. Returns false when the membership was not present. */
    fun removeMember(clanId: ClanId, agentId: AgentId): Boolean

    /**
     * Hard-delete [clanId] and (via cascade) its membership rows. Returns the former
     * member ids for event fan-out, or empty when the clan was already gone.
     */
    fun dissolve(clanId: ClanId): List<AgentId>
}

/** Result of [ClanRegistry.createClan]. */
sealed interface CreateClanOutcome {
    data class Created(val clan: Clan) : CreateClanOutcome
    data object NameTaken : CreateClanOutcome
    data class AlreadyInClan(val existing: ClanId) : CreateClanOutcome
}

/** Result of [ClanRegistry.addMember]. */
sealed interface AddMemberOutcome {
    data object Added : AddMemberOutcome
    data class AlreadyInClan(val existing: ClanId) : AddMemberOutcome
    data object ClanNotFound : AddMemberOutcome
}
