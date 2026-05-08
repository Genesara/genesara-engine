package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerk
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentPerksSnapshot
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_PERKS
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqAgentPerksRegistry(
    private val dsl: DSLContext,
    private val perks: PerkLookup,
) : AgentPerksRegistry {

    @Transactional(readOnly = true)
    override fun snapshot(agent: AgentId): AgentPerksSnapshot {
        val rows = dsl.select(
            AGENT_PERKS.SKILL_ID,
            AGENT_PERKS.MILESTONE_LEVEL,
            AGENT_PERKS.PERK_ID,
            AGENT_PERKS.CHOSEN_AT_TICK,
        )
            .from(AGENT_PERKS)
            .where(AGENT_PERKS.AGENT_ID.eq(agent.id))
            .orderBy(AGENT_PERKS.SKILL_ID.asc(), AGENT_PERKS.MILESTONE_LEVEL.asc())
            .fetch()
            .map { row ->
                AgentPerk(
                    skill = SkillId(row[AGENT_PERKS.SKILL_ID]!!),
                    milestoneLevel = row[AGENT_PERKS.MILESTONE_LEVEL]!!,
                    perkId = PerkId(row[AGENT_PERKS.PERK_ID]!!),
                    chosenAtTick = row[AGENT_PERKS.CHOSEN_AT_TICK]!!,
                )
            }
        return AgentPerksSnapshot(rows)
    }

    @Transactional
    override fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult {
        val catalog = perks.byId(perk) ?: return RecordPerkResult.UnknownPerk(perk)
        readExistingPick(agent, catalog.skill, catalog.milestoneLevel)?.let { existing ->
            return RecordPerkResult.MilestoneAlreadyChosen(catalog.skill, catalog.milestoneLevel, existing)
        }
        return insertTranslatingRace(agent, catalog.skill, catalog.milestoneLevel, perk, tick)
    }

    /** PK race fallback: typed rejection rather than a 500 if the pre-check is bypassed. */
    private fun insertTranslatingRace(
        agent: AgentId,
        skill: SkillId,
        milestoneLevel: Int,
        perk: PerkId,
        tick: Long,
    ): RecordPerkResult = try {
        dsl.insertInto(AGENT_PERKS)
            .set(AGENT_PERKS.AGENT_ID, agent.id)
            .set(AGENT_PERKS.SKILL_ID, skill.value)
            .set(AGENT_PERKS.MILESTONE_LEVEL, milestoneLevel)
            .set(AGENT_PERKS.PERK_ID, perk.value)
            .set(AGENT_PERKS.CHOSEN_AT_TICK, tick)
            .execute()
        RecordPerkResult.Recorded
    } catch (_: DuplicateKeyException) {
        val existing = readExistingPick(agent, skill, milestoneLevel)
            ?: throw IllegalStateException(
                "DB reports duplicate (agent=${agent.id}, skill=${skill.value}, " +
                    "milestone=$milestoneLevel) but no row was readable",
            )
        RecordPerkResult.MilestoneAlreadyChosen(skill, milestoneLevel, existing)
    }

    private fun readExistingPick(agent: AgentId, skill: SkillId, milestoneLevel: Int): PerkId? =
        dsl.select(AGENT_PERKS.PERK_ID)
            .from(AGENT_PERKS)
            .where(AGENT_PERKS.AGENT_ID.eq(agent.id))
            .and(AGENT_PERKS.SKILL_ID.eq(skill.value))
            .and(AGENT_PERKS.MILESTONE_LEVEL.eq(milestoneLevel))
            .fetchOne(AGENT_PERKS.PERK_ID)
            ?.let { PerkId(it) }
}
