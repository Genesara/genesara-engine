package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AdminSkillXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import org.springframework.dao.DuplicateKeyException
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_SKILLS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_SKILL_RECOMMENDATIONS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_SKILL_SLOTS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * jOOQ-backed [AgentSkillsRegistry].
 *
 * Every method is `@Transactional` (`Propagation.REQUIRED`), so a constraint violation
 * marks the enclosing tick transaction rollback-only — one bad gather kills every other
 * reducer's effects in that tick. Switch a verb to `Propagation.REQUIRES_NEW` if a future
 * slice ever needs partial-tick commit.
 *
 * Constants below are game-design knobs; lift to a `SkillBalanceProperties` mirror of
 * `ClassDefinitionProperties` if they need to become tunable.
 */
@Component
internal class JooqAgentSkillsRegistry(
    private val dsl: DSLContext,
    private val skills: SkillLookup,
) : AgentSkillsRegistry {

    @Transactional(readOnly = true)
    override fun snapshot(agent: AgentId): AgentSkillsSnapshot {
        val xpBySkill = readXpBySkill(agent)
        val slotIndexBySkill = readSlotIndexBySkill(agent)
        val recommendCountBySkill = readRecommendCountBySkill(agent)

        val touchedIds = xpBySkill.keys + slotIndexBySkill.keys + recommendCountBySkill.keys
        val perSkill = touchedIds.mapNotNull { key ->
            val skillId = SkillId(key)
            skills.byId(skillId) ?: return@mapNotNull null
            val xp = xpBySkill[key] ?: 0
            skillId to AgentSkillState(
                skill = skillId,
                xp = xp,
                level = levelFromXp(xp),
                slotIndex = slotIndexBySkill[key],
                recommendCount = recommendCountBySkill[key] ?: 0,
            )
        }.toMap()

        return AgentSkillsSnapshot(
            perSkill = perSkill,
            slotCount = computeSlotCount(agent),
            slotsFilled = slotIndexBySkill.size,
        )
    }

    @Transactional(readOnly = true)
    override fun slottedSkillLevel(agent: AgentId, skill: SkillId): Int {
        val xp = dsl.select(AGENT_SKILLS.XP)
            .from(AGENT_SKILL_SLOTS)
            .leftJoin(AGENT_SKILLS).on(
                AGENT_SKILLS.AGENT_ID.eq(AGENT_SKILL_SLOTS.AGENT_ID)
                    .and(AGENT_SKILLS.SKILL_ID.eq(AGENT_SKILL_SLOTS.SKILL_ID)),
            )
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value))
            .fetchOne(AGENT_SKILLS.XP) ?: return 0
        return levelFromXp(xp)
    }

    private fun readXpBySkill(agent: AgentId): Map<String, Int> =
        dsl.select(AGENT_SKILLS.SKILL_ID, AGENT_SKILLS.XP)
            .from(AGENT_SKILLS)
            .where(AGENT_SKILLS.AGENT_ID.eq(agent.id))
            .fetch()
            .associate { row -> row[AGENT_SKILLS.SKILL_ID]!! to row[AGENT_SKILLS.XP]!! }

    private fun readSlotIndexBySkill(agent: AgentId): Map<String, Int> =
        dsl.select(AGENT_SKILL_SLOTS.SKILL_ID, AGENT_SKILL_SLOTS.SLOT_INDEX)
            .from(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .fetch()
            .associate { row -> row[AGENT_SKILL_SLOTS.SKILL_ID]!! to row[AGENT_SKILL_SLOTS.SLOT_INDEX]!! }

    private fun readRecommendCountBySkill(agent: AgentId): Map<String, Int> =
        dsl.select(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID, AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT)
            .from(AGENT_SKILL_RECOMMENDATIONS)
            .where(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID.eq(agent.id))
            .fetch()
            .associate { row -> row[AGENT_SKILL_RECOMMENDATIONS.SKILL_ID]!! to row[AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT]!! }

    /**
     * Read-modify-write inside the transaction. Per-tick processing is sequential per
     * agent, so the (agent, skill) row never races with itself.
     */
    @Transactional
    override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
        require(delta >= 0) { "xp delta must be non-negative; got $delta" }
        if (!isSkillSlotted(agent, skill)) return AddXpResult.Unslotted
        if (delta == 0) return AddXpResult.Accrued(emptyList())

        val oldXp = readSkillXp(agent, skill)
        val newXp = oldXp + delta
        upsertSkillXp(agent, skill, newXp)

        return AddXpResult.Accrued(MILESTONE_THRESHOLDS.filter { it in (oldXp + 1)..newXp })
    }

    private fun isSkillSlotted(agent: AgentId, skill: SkillId): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(AGENT_SKILL_SLOTS)
                .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
                .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value)),
        )

    private fun readSkillXp(agent: AgentId, skill: SkillId): Int =
        dsl.select(AGENT_SKILLS.XP)
            .from(AGENT_SKILLS)
            .where(AGENT_SKILLS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILLS.SKILL_ID.eq(skill.value))
            .fetchOne(AGENT_SKILLS.XP)
            ?: 0

    private fun upsertSkillXp(agent: AgentId, skill: SkillId, newXp: Int) {
        dsl.insertInto(AGENT_SKILLS)
            .set(AGENT_SKILLS.AGENT_ID, agent.id)
            .set(AGENT_SKILLS.SKILL_ID, skill.value)
            .set(AGENT_SKILLS.XP, newXp)
            .onConflict(AGENT_SKILLS.AGENT_ID, AGENT_SKILLS.SKILL_ID)
            .doUpdate()
            .set(AGENT_SKILLS.XP, newXp)
            .execute()
    }

    @Transactional
    override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? {
        if (isSkillSlotted(agent, skill)) return null
        if (allSlotsFull(agent)) return null

        val current = readRecommendationRow(agent, skill)
        val currentCount = current?.first ?: 0
        if (currentCount >= RECOMMEND_CAP) return null

        val lastTick = current?.second ?: Long.MIN_VALUE
        if (currentCount > 0 && tick - lastTick < RECOMMEND_COOLDOWN_TICKS) return null

        val newCount = currentCount + 1
        upsertRecommendation(agent, skill, newCount, tick)
        return newCount
    }

    private fun allSlotsFull(agent: AgentId): Boolean {
        val slotsFilled = dsl.fetchCount(AGENT_SKILL_SLOTS, AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
        return slotsFilled >= computeSlotCount(agent)
    }

    private fun readRecommendationRow(agent: AgentId, skill: SkillId): Pair<Int, Long>? =
        dsl.select(
            AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT,
            AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK,
        )
            .from(AGENT_SKILL_RECOMMENDATIONS)
            .where(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID.eq(skill.value))
            .fetchOne()
            ?.let { it[AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT]!! to it[AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK]!! }

    private fun upsertRecommendation(agent: AgentId, skill: SkillId, count: Int, tick: Long) {
        dsl.insertInto(AGENT_SKILL_RECOMMENDATIONS)
            .set(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID, agent.id)
            .set(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID, skill.value)
            .set(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT, count)
            .set(AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK, tick)
            .onConflict(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID, AGENT_SKILL_RECOMMENDATIONS.SKILL_ID)
            .doUpdate()
            .set(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT, count)
            .set(AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK, tick)
            .execute()
    }

    @Transactional
    override fun adminSetSkillXp(agent: AgentId, skill: SkillId, xp: Int): AdminSkillXpResult {
        require(xp >= 0) { "xp must be non-negative; got $xp" }
        val oldXp = readSkillXp(agent, skill)
        upsertSkillXp(agent, skill, xp)
        val crossed = if (xp > oldXp) MILESTONE_THRESHOLDS.filter { it in (oldXp + 1)..xp } else emptyList()
        return AdminSkillXpResult(previousXp = oldXp, newXp = xp, crossedMilestones = crossed)
    }

    @Transactional
    override fun adminForceUnequip(agent: AgentId, skill: SkillId): Boolean =
        dsl.deleteFrom(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value))
            .execute() > 0

    @Transactional
    override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? {
        val slotCount = computeSlotCount(agent)
        if (slotIndex !in 0 until slotCount) {
            return SkillSlotError.SlotIndexOutOfRange(slotIndex, slotCount)
        }
        requireDiscovered(agent, skill)?.let { return it }
        existingSlotForSkill(agent, skill)?.let { return SkillSlotError.SkillAlreadySlotted(skill, it) }
        occupantOf(agent, slotIndex)?.let { return SkillSlotError.SlotOccupied(slotIndex, SkillId(it)) }
        return insertSlotTranslatingRace(agent, skill, slotIndex)
    }

    /**
     * Discovery gate: the catalog is hidden, so the agent must have been recommended for
     * this skill (recommend_count >= 1) before they can slot it. The recommendation loop
     * is the single discovery path.
     */
    private fun requireDiscovered(agent: AgentId, skill: SkillId): SkillSlotError? {
        val recommendCount = dsl.select(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT)
            .from(AGENT_SKILL_RECOMMENDATIONS)
            .where(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID.eq(skill.value))
            .fetchOne(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT) ?: 0
        return if (recommendCount == 0) SkillSlotError.SkillNotDiscovered(skill) else null
    }

    private fun existingSlotForSkill(agent: AgentId, skill: SkillId): Int? =
        dsl.select(AGENT_SKILL_SLOTS.SLOT_INDEX)
            .from(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value))
            .fetchOne(AGENT_SKILL_SLOTS.SLOT_INDEX)

    private fun occupantOf(agent: AgentId, slotIndex: Int): String? =
        dsl.select(AGENT_SKILL_SLOTS.SKILL_ID)
            .from(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_SLOTS.SLOT_INDEX.eq(slotIndex))
            .fetchOne(AGENT_SKILL_SLOTS.SKILL_ID)

    /**
     * Concurrent setSlot calls (rare — one MCP session per agent — but possible) can race
     * past the pre-checks. The DB enforces correctness via PK (slot_index) + UNIQUE
     * (skill_id); translate the resulting [DuplicateKeyException] into the matching typed
     * rejection so callers see `SlotOccupied` / `SkillAlreadySlotted` rather than a 500.
     */
    private fun insertSlotTranslatingRace(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? =
        try {
            dsl.insertInto(AGENT_SKILL_SLOTS)
                .set(AGENT_SKILL_SLOTS.AGENT_ID, agent.id)
                .set(AGENT_SKILL_SLOTS.SLOT_INDEX, slotIndex)
                .set(AGENT_SKILL_SLOTS.SKILL_ID, skill.value)
                .execute()
            null
        } catch (e: DuplicateKeyException) {
            classifyDuplicateOrRethrow(agent, skill, slotIndex, e)
        }

    private fun classifyDuplicateOrRethrow(
        agent: AgentId,
        skill: SkillId,
        slotIndex: Int,
        original: DuplicateKeyException,
    ): SkillSlotError {
        occupantOf(agent, slotIndex)?.let { occupant ->
            if (occupant != skill.value) return SkillSlotError.SlotOccupied(slotIndex, SkillId(occupant))
        }
        existingSlotForSkill(agent, skill)?.let { return SkillSlotError.SkillAlreadySlotted(skill, it) }
        throw original
    }

    /**
     * Slot count = `BASE + floor(level / 10) + factionRankBonus`. Returns [BASE_SLOTS]
     * when the agent row is missing — defensive; a missing row shouldn't crash a read.
     * Level and faction rank are read in one round-trip.
     */
    private fun computeSlotCount(agent: AgentId): Int {
        val row = dsl.select(AGENTS.LEVEL, AGENTS.FACTION_RANK)
            .from(AGENTS)
            .where(AGENTS.ID.eq(agent.id))
            .fetchOne()
            ?: return BASE_SLOTS
        val level = row[AGENTS.LEVEL]!!
        val factionBonus = FactionRank.parseOrNull(row[AGENTS.FACTION_RANK])?.slotBonus ?: 0
        return BASE_SLOTS + (level / SLOTS_PER_LEVEL_GROUP) + factionBonus
    }

    private fun levelFromXp(xp: Int): Int = xp / XP_PER_LEVEL

    private companion object {
        const val BASE_SLOTS = 8
        const val SLOTS_PER_LEVEL_GROUP = 10
        const val XP_PER_LEVEL = 10
        const val RECOMMEND_CAP = 3

        /**
         * Per-skill suppression window after a `SkillRecommended` event fires for
         * (agent, skill). Dedupes near-adjacent actions that target the same skill
         * (e.g. `harvest(FISH)` immediately followed by `consume(FISH)`) — the
         * recommendation is the *discovery* surface, not a per-action telemetry
         * ping. At 1s/tick the 60-tick window covers a typical action chain while
         * still allowing all 3 recommendations to fire over a multi-minute session.
         */
        const val RECOMMEND_COOLDOWN_TICKS = 60L
        val MILESTONE_THRESHOLDS = listOf(50, 100, 150)
    }
}
