package dev.gvart.genesara.world.clan.internal

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.AddMemberOutcome
import dev.gvart.genesara.world.Clan
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanMember
import dev.gvart.genesara.world.ClanMembership
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.FactionId
import dev.gvart.genesara.world.internal.jooq.tables.references.CLANS
import dev.gvart.genesara.world.internal.jooq.tables.references.CLAN_MEMBERS
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqClanRegistry(
    private val dsl: DSLContext,
) : ClanRegistry {

    @Transactional
    override fun createClan(name: String, founder: AgentId, tick: Long): CreateClanOutcome {
        clanOf(founder)?.let { return CreateClanOutcome.AlreadyInClan(it.clan.id) }
        if (nameExists(name)) return CreateClanOutcome.NameTaken
        val clanId = ClanId(UUID.randomUUID())
        return try {
            dsl.insertInto(CLANS)
                .set(CLANS.ID, clanId.value)
                .set(CLANS.NAME, name)
                .set(CLANS.FOUNDED_AT_TICK, tick)
                .execute()
            dsl.insertInto(CLAN_MEMBERS)
                .set(CLAN_MEMBERS.CLAN_ID, clanId.value)
                .set(CLAN_MEMBERS.AGENT_ID, founder.id)
                .set(CLAN_MEMBERS.CLAN_RANK, ClanRank.ARCHON.name)
                .set(CLAN_MEMBERS.JOINED_AT_TICK, tick)
                .execute()
            CreateClanOutcome.Created(Clan(clanId, name, factionId = null, foundedAtTick = tick))
        } catch (_: DuplicateKeyException) {
            // Lost a race between the pre-checks and the inserts; re-classify the loser.
            clanOf(founder)?.let { return CreateClanOutcome.AlreadyInClan(it.clan.id) }
            CreateClanOutcome.NameTaken
        }
    }

    @Transactional(readOnly = true)
    override fun findClan(clanId: ClanId): Clan? =
        dsl.select(CLANS.ID, CLANS.NAME, CLANS.FACTION_ID, CLANS.FOUNDED_AT_TICK)
            .from(CLANS)
            .where(CLANS.ID.eq(clanId.value))
            .fetchOne()
            ?.toClan()

    @Transactional(readOnly = true)
    override fun clanOf(agentId: AgentId): ClanMembership? {
        val row = dsl.select(
            CLANS.ID, CLANS.NAME, CLANS.FACTION_ID, CLANS.FOUNDED_AT_TICK,
            CLAN_MEMBERS.CLAN_RANK, CLAN_MEMBERS.FACTION_RANK,
        )
            .from(CLAN_MEMBERS)
            .join(CLANS).on(CLANS.ID.eq(CLAN_MEMBERS.CLAN_ID))
            .where(CLAN_MEMBERS.AGENT_ID.eq(agentId.id))
            .fetchOne()
            ?: return null
        return ClanMembership(
            clan = row.toClan(),
            clanRank = ClanRank.valueOf(row[CLAN_MEMBERS.CLAN_RANK]!!),
            factionRank = row[CLAN_MEMBERS.FACTION_RANK]?.let { FactionRank.valueOf(it) },
        )
    }

    @Transactional(readOnly = true)
    override fun roster(clanId: ClanId): List<ClanMember> =
        dsl.select(
            CLAN_MEMBERS.CLAN_ID, CLAN_MEMBERS.AGENT_ID, CLAN_MEMBERS.CLAN_RANK,
            CLAN_MEMBERS.FACTION_RANK, CLAN_MEMBERS.JOINED_AT_TICK,
        )
            .from(CLAN_MEMBERS)
            .where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value))
            .orderBy(CLAN_MEMBERS.JOINED_AT_TICK.asc())
            .fetch()
            .map { row ->
                ClanMember(
                    clanId = ClanId(row[CLAN_MEMBERS.CLAN_ID]!!),
                    agentId = AgentId(row[CLAN_MEMBERS.AGENT_ID]!!),
                    clanRank = ClanRank.valueOf(row[CLAN_MEMBERS.CLAN_RANK]!!),
                    factionRank = row[CLAN_MEMBERS.FACTION_RANK]?.let { FactionRank.valueOf(it) },
                    joinedAtTick = row[CLAN_MEMBERS.JOINED_AT_TICK]!!,
                )
            }

    @Transactional(readOnly = true)
    override fun memberCount(clanId: ClanId): Int =
        dsl.fetchCount(CLAN_MEMBERS, CLAN_MEMBERS.CLAN_ID.eq(clanId.value))

    @Transactional
    override fun addMember(clanId: ClanId, agentId: AgentId, rank: ClanRank, tick: Long): AddMemberOutcome {
        if (findClan(clanId) == null) return AddMemberOutcome.ClanNotFound
        clanOf(agentId)?.let { return AddMemberOutcome.AlreadyInClan(it.clan.id) }
        return try {
            dsl.insertInto(CLAN_MEMBERS)
                .set(CLAN_MEMBERS.CLAN_ID, clanId.value)
                .set(CLAN_MEMBERS.AGENT_ID, agentId.id)
                .set(CLAN_MEMBERS.CLAN_RANK, rank.name)
                .set(CLAN_MEMBERS.JOINED_AT_TICK, tick)
                .execute()
            AddMemberOutcome.Added
        } catch (_: DuplicateKeyException) {
            clanOf(agentId)?.let { return AddMemberOutcome.AlreadyInClan(it.clan.id) }
            AddMemberOutcome.ClanNotFound
        }
    }

    @Transactional
    override fun changeClanRank(clanId: ClanId, agentId: AgentId, newRank: ClanRank): Boolean =
        dsl.update(CLAN_MEMBERS)
            .set(CLAN_MEMBERS.CLAN_RANK, newRank.name)
            .where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value))
            .and(CLAN_MEMBERS.AGENT_ID.eq(agentId.id))
            .execute() > 0

    @Transactional
    override fun removeMember(clanId: ClanId, agentId: AgentId): Boolean =
        dsl.deleteFrom(CLAN_MEMBERS)
            .where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value))
            .and(CLAN_MEMBERS.AGENT_ID.eq(agentId.id))
            .execute() > 0

    @Transactional
    override fun dissolve(clanId: ClanId): List<AgentId> {
        val members = dsl.select(CLAN_MEMBERS.AGENT_ID)
            .from(CLAN_MEMBERS)
            .where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value))
            .fetch(CLAN_MEMBERS.AGENT_ID)
            .map { AgentId(it!!) }
        // clan_members cascade-deletes with the clan row (FK ON DELETE CASCADE).
        val deleted = dsl.deleteFrom(CLANS).where(CLANS.ID.eq(clanId.value)).execute()
        return if (deleted > 0) members else emptyList()
    }

    private fun nameExists(name: String): Boolean =
        dsl.fetchExists(dsl.selectOne().from(CLANS).where(CLANS.NAME.eq(name)))

    private fun Record.toClan(): Clan = Clan(
        id = ClanId(this[CLANS.ID]!!),
        name = this[CLANS.NAME]!!,
        factionId = this[CLANS.FACTION_ID]?.let { FactionId(it) },
        foundedAtTick = this[CLANS.FOUNDED_AT_TICK]!!,
    )
}
