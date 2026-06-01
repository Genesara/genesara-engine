package dev.gvart.genesara.world.clan.internal

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.CreateFactionOutcome
import dev.gvart.genesara.world.Faction
import dev.gvart.genesara.world.FactionId
import dev.gvart.genesara.world.FactionRegistry
import dev.gvart.genesara.world.LeaveFactionResult
import dev.gvart.genesara.world.internal.jooq.tables.references.CLANS
import dev.gvart.genesara.world.internal.jooq.tables.references.CLAN_MEMBERS
import dev.gvart.genesara.world.internal.jooq.tables.references.FACTIONS
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqFactionRegistry(
    private val dsl: DSLContext,
) : FactionRegistry {

    @Transactional
    override fun createFaction(name: String, founderClanId: ClanId, founderArchon: AgentId, tick: Long): CreateFactionOutcome {
        if (nameExists(name)) return CreateFactionOutcome.NameTaken
        val factionId = FactionId(UUID.randomUUID())
        return try {
            dsl.insertInto(FACTIONS)
                .set(FACTIONS.ID, factionId.value)
                .set(FACTIONS.NAME, name)
                .set(FACTIONS.FOUNDED_AT_TICK, tick)
                .execute()
            dsl.update(CLANS).set(CLANS.FACTION_ID, factionId.value).where(CLANS.ID.eq(founderClanId.value)).execute()
            // Founding clan: everyone Pact, then the founding Archon promoted to Sovereign.
            dsl.update(CLAN_MEMBERS)
                .set(CLAN_MEMBERS.FACTION_RANK, FactionRank.PACT.name)
                .where(CLAN_MEMBERS.CLAN_ID.eq(founderClanId.value))
                .execute()
            dsl.update(CLAN_MEMBERS)
                .set(CLAN_MEMBERS.FACTION_RANK, FactionRank.SOVEREIGN.name)
                .where(CLAN_MEMBERS.CLAN_ID.eq(founderClanId.value))
                .and(CLAN_MEMBERS.AGENT_ID.eq(founderArchon.id))
                .execute()
            CreateFactionOutcome.Created(Faction(factionId, name, tick), membersWithRanks(founderClanId))
        } catch (_: DuplicateKeyException) {
            CreateFactionOutcome.NameTaken
        }
    }

    @Transactional(readOnly = true)
    override fun findFaction(factionId: FactionId): Faction? =
        dsl.select(FACTIONS.NAME, FACTIONS.FOUNDED_AT_TICK)
            .from(FACTIONS)
            .where(FACTIONS.ID.eq(factionId.value))
            .fetchOne()
            ?.let { Faction(factionId, it[FACTIONS.NAME]!!, it[FACTIONS.FOUNDED_AT_TICK]!!) }

    @Transactional(readOnly = true)
    override fun factionOf(clanId: ClanId): Faction? {
        val factionId = dsl.select(CLANS.FACTION_ID).from(CLANS).where(CLANS.ID.eq(clanId.value)).fetchOne(CLANS.FACTION_ID)
            ?: return null
        return findFaction(FactionId(factionId))
    }

    @Transactional
    override fun joinFaction(factionId: FactionId, clanId: ClanId): Map<AgentId, FactionRank> {
        dsl.update(CLANS).set(CLANS.FACTION_ID, factionId.value).where(CLANS.ID.eq(clanId.value)).execute()
        dsl.update(CLAN_MEMBERS)
            .set(CLAN_MEMBERS.FACTION_RANK, FactionRank.PACT.name)
            .where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value))
            .execute()
        return membersWithRanks(clanId)
    }

    @Transactional
    override fun leaveFaction(clanId: ClanId): LeaveFactionResult {
        val factionId = FactionId(
            dsl.select(CLANS.FACTION_ID).from(CLANS).where(CLANS.ID.eq(clanId.value)).fetchOne(CLANS.FACTION_ID)
                ?: error("leaveFaction: clan $clanId is in no faction"),
        )
        val clearedAgents = dsl.select(CLAN_MEMBERS.AGENT_ID)
            .from(CLAN_MEMBERS)
            .where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value))
            .fetch(CLAN_MEMBERS.AGENT_ID)
            .map { AgentId(it!!) }
        dsl.update(CLAN_MEMBERS).setNull(CLAN_MEMBERS.FACTION_RANK).where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value)).execute()
        dsl.update(CLANS).setNull(CLANS.FACTION_ID).where(CLANS.ID.eq(clanId.value)).execute()
        val remaining = dsl.fetchCount(CLANS, CLANS.FACTION_ID.eq(factionId.value))
        return LeaveFactionResult(factionId, clearedAgents, factionEmptied = remaining == 0)
    }

    @Transactional
    override fun setFactionRank(agentId: AgentId, rank: FactionRank) {
        dsl.update(CLAN_MEMBERS)
            .set(CLAN_MEMBERS.FACTION_RANK, rank.name)
            .where(CLAN_MEMBERS.AGENT_ID.eq(agentId.id))
            .execute()
    }

    @Transactional(readOnly = true)
    override fun memberClanIds(factionId: FactionId): List<ClanId> =
        dsl.select(CLANS.ID).from(CLANS).where(CLANS.FACTION_ID.eq(factionId.value)).fetch(CLANS.ID).map { ClanId(it!!) }

    @Transactional(readOnly = true)
    override fun agentsInFaction(factionId: FactionId): List<AgentId> =
        dsl.select(CLAN_MEMBERS.AGENT_ID)
            .from(CLAN_MEMBERS)
            .join(CLANS).on(CLANS.ID.eq(CLAN_MEMBERS.CLAN_ID))
            .where(CLANS.FACTION_ID.eq(factionId.value))
            .fetch(CLAN_MEMBERS.AGENT_ID)
            .map { AgentId(it!!) }

    @Transactional
    override fun deleteFaction(factionId: FactionId) {
        dsl.deleteFrom(FACTIONS).where(FACTIONS.ID.eq(factionId.value)).execute()
    }

    private fun nameExists(name: String): Boolean =
        dsl.fetchExists(dsl.selectOne().from(FACTIONS).where(FACTIONS.NAME.eq(name)))

    private fun membersWithRanks(clanId: ClanId): Map<AgentId, FactionRank> =
        dsl.select(CLAN_MEMBERS.AGENT_ID, CLAN_MEMBERS.FACTION_RANK)
            .from(CLAN_MEMBERS)
            .where(CLAN_MEMBERS.CLAN_ID.eq(clanId.value))
            .fetch()
            .mapNotNull { row ->
                row[CLAN_MEMBERS.FACTION_RANK]?.let { AgentId(row[CLAN_MEMBERS.AGENT_ID]!!) to FactionRank.valueOf(it) }
            }
            .toMap()
}
