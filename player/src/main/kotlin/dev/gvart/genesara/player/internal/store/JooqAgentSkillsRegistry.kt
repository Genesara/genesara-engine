package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsSnapshot
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
 * jOOQ-backed implementation of [AgentSkillsRegistry].
 *
 * **Transactional contract.** Every public method is `@Transactional` with the
 * default `Propagation.REQUIRED`, which means they join an enclosing transaction
 * when called from `WorldTickHandler.onTick` (also `@Transactional`). A registry-
 * level constraint violation will therefore mark the *entire tick's* transaction
 * as rollback-only — one bad gather kills every other reducer's effects in that
 * tick. That's the right call for now (consistency over availability), but if a
 * future slice introduces a verb where partial-tick commit is desired, switch
 * that verb's registry calls to `Propagation.REQUIRES_NEW`.
 *
 * **Constants** (milestone thresholds, recommend cap, cooldown) are hardcoded.
 * They're game-design knobs and there's no balance YAML for player-side numbers
 * yet. If they need to become tunable, lift into a `SkillBalanceProperties`
 * mirror of the existing `ClassDefinitionProperties` pattern.
 */
@Component
internal class JooqAgentSkillsRegistry(
    private val dsl: DSLContext,
    private val skills: SkillLookup,
) : AgentSkillsRegistry {

    @Transactional(readOnly = true)
    override fun snapshot(agent: AgentId): AgentSkillsSnapshot {
        val xpRows = dsl.select(AGENT_SKILLS.SKILL_ID, AGENT_SKILLS.XP)
            .from(AGENT_SKILLS)
            .where(AGENT_SKILLS.AGENT_ID.eq(agent.id))
            .fetch()
            .associate { row -> row[AGENT_SKILLS.SKILL_ID]!! to row[AGENT_SKILLS.XP]!! }

        val slotRows = dsl.select(AGENT_SKILL_SLOTS.SKILL_ID, AGENT_SKILL_SLOTS.SLOT_INDEX)
            .from(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .fetch()
            .associate { row -> row[AGENT_SKILL_SLOTS.SKILL_ID]!! to row[AGENT_SKILL_SLOTS.SLOT_INDEX]!! }

        val recRows = dsl.select(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID, AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT)
            .from(AGENT_SKILL_RECOMMENDATIONS)
            .where(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID.eq(agent.id))
            .fetch()
            .associate { row -> row[AGENT_SKILL_RECOMMENDATIONS.SKILL_ID]!! to row[AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT]!! }

        // Catalog is HIDDEN. Only skills the agent has touched in some way (recommended,
        // slotted, or accrued XP) appear in the snapshot. The full catalog is not
        // exposed via any read path — agents discover skills through gameplay.
        val touchedIds: Set<String> = xpRows.keys + slotRows.keys + recRows.keys
        val perSkill = touchedIds.mapNotNull { key ->
            val skillId = SkillId(key)
            // Drop ids that no longer exist in the catalog (e.g. an old yaml entry
            // removed between sessions). Defensive — shouldn't happen in practice.
            skills.byId(skillId) ?: return@mapNotNull null
            val xp = xpRows[key] ?: 0
            skillId to AgentSkillState(
                skill = skillId,
                xp = xp,
                level = levelFromXp(xp),
                slotIndex = slotRows[key],
                recommendCount = recRows[key] ?: 0,
            )
        }.toMap()

        val slotCount = computeSlotCount(agent)
        return AgentSkillsSnapshot(
            perSkill = perSkill,
            slotCount = slotCount,
            slotsFilled = slotRows.size,
        )
    }

    @Transactional
    override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
        require(delta >= 0) { "xp delta must be non-negative; got $delta" }

        val isSlotted = dsl.fetchExists(
            dsl.selectOne()
                .from(AGENT_SKILL_SLOTS)
                .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
                .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value)),
        )
        if (!isSlotted) return AddXpResult.Unslotted
        if (delta == 0) return AddXpResult.Accrued(emptyList())

        // Read-modify-write inside the transaction. Concurrent writes for the same
        // (agent, skill) would race, but per-tick processing is sequential per agent
        // so this is safe under current architecture.
        val oldXp = dsl.select(AGENT_SKILLS.XP)
            .from(AGENT_SKILLS)
            .where(AGENT_SKILLS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILLS.SKILL_ID.eq(skill.value))
            .fetchOne(AGENT_SKILLS.XP)
            ?: 0
        val newXp = oldXp + delta

        dsl.insertInto(AGENT_SKILLS)
            .set(AGENT_SKILLS.AGENT_ID, agent.id)
            .set(AGENT_SKILLS.SKILL_ID, skill.value)
            .set(AGENT_SKILLS.XP, newXp)
            .onConflict(AGENT_SKILLS.AGENT_ID, AGENT_SKILLS.SKILL_ID)
            .doUpdate()
            .set(AGENT_SKILLS.XP, newXp)
            .execute()

        return AddXpResult.Accrued(MILESTONE_THRESHOLDS.filter { it in (oldXp + 1)..newXp })
    }

    @Transactional
    override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? {
        // Skip if already slotted.
        val isSlotted = dsl.fetchExists(
            dsl.selectOne()
                .from(AGENT_SKILL_SLOTS)
                .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
                .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value)),
        )
        if (isSlotted) return null

        // Skip if all slots are full.
        val slotsFilled = dsl.fetchCount(
            AGENT_SKILL_SLOTS,
            AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id),
        )
        val slotCount = computeSlotCount(agent)
        if (slotsFilled >= slotCount) return null

        // Read current recommendation row (if any) and apply cap + cooldown gates.
        val current = dsl.select(
            AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT,
            AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK,
        )
            .from(AGENT_SKILL_RECOMMENDATIONS)
            .where(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID.eq(skill.value))
            .fetchOne()

        val currentCount = current?.get(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT) ?: 0
        if (currentCount >= RECOMMEND_CAP) return null

        val lastTick = current?.get(AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK) ?: Long.MIN_VALUE
        // First-ever recommendation always fires (no prior tick to cool down from).
        if (currentCount > 0 && tick - lastTick < RECOMMEND_COOLDOWN_TICKS) return null

        val newCount = currentCount + 1
        dsl.insertInto(AGENT_SKILL_RECOMMENDATIONS)
            .set(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID, agent.id)
            .set(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID, skill.value)
            .set(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT, newCount)
            .set(AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK, tick)
            .onConflict(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID, AGENT_SKILL_RECOMMENDATIONS.SKILL_ID)
            .doUpdate()
            .set(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT, newCount)
            .set(AGENT_SKILL_RECOMMENDATIONS.LAST_RECOMMENDED_AT_TICK, tick)
            .execute()
        return newCount
    }

    @Transactional
    override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? {
        val slotCount = computeSlotCount(agent)
        if (slotIndex !in 0 until slotCount) {
            return SkillSlotError.SlotIndexOutOfRange(slotIndex, slotCount)
        }

        // Discovery gate: the catalog is hidden, so the agent must have actually been
        // recommended for this skill (recommend_count >= 1) before they can slot it.
        // Slotting an undiscovered skill is rejected even if the slot is empty and the
        // skill exists in the catalog. This forces the recommendation loop to be the
        // single discovery path.
        val recommendCount = dsl.select(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT)
            .from(AGENT_SKILL_RECOMMENDATIONS)
            .where(AGENT_SKILL_RECOMMENDATIONS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_RECOMMENDATIONS.SKILL_ID.eq(skill.value))
            .fetchOne(AGENT_SKILL_RECOMMENDATIONS.RECOMMEND_COUNT) ?: 0
        if (recommendCount == 0) {
            return SkillSlotError.SkillNotDiscovered(skill)
        }

        // Reject if the skill is already slotted somewhere — slot-uniqueness is
        // checked here because it's the more agent-actionable error.
        val existingSlotForSkill = dsl.select(AGENT_SKILL_SLOTS.SLOT_INDEX)
            .from(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value))
            .fetchOne(AGENT_SKILL_SLOTS.SLOT_INDEX)
        if (existingSlotForSkill != null) {
            return SkillSlotError.SkillAlreadySlotted(skill, existingSlotForSkill)
        }

        // Reject if the target slot is already occupied (by a different skill).
        val occupant = dsl.select(AGENT_SKILL_SLOTS.SKILL_ID)
            .from(AGENT_SKILL_SLOTS)
            .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
            .and(AGENT_SKILL_SLOTS.SLOT_INDEX.eq(slotIndex))
            .fetchOne(AGENT_SKILL_SLOTS.SKILL_ID)
        if (occupant != null) {
            return SkillSlotError.SlotOccupied(slotIndex, SkillId(occupant))
        }

        // Race translation: two concurrent setSlot calls (rare — one MCP session per
        // agent — but possible) could both pass the pre-checks above and race on the
        // INSERT. The DB enforces correctness via PK (slot_index) + UNIQUE (skill_id);
        // we translate the resulting constraint exception to the typed rejection so
        // callers see a `SlotOccupied` / `SkillAlreadySlotted` rather than a 500.
        return try {
            dsl.insertInto(AGENT_SKILL_SLOTS)
                .set(AGENT_SKILL_SLOTS.AGENT_ID, agent.id)
                .set(AGENT_SKILL_SLOTS.SLOT_INDEX, slotIndex)
                .set(AGENT_SKILL_SLOTS.SKILL_ID, skill.value)
                .execute()
            null
        } catch (e: DuplicateKeyException) {
            // Re-read to discover which constraint actually fired. The race window is
            // narrow so by the time we get here at least one of the two checks will
            // succeed.
            val nowOccupiedBy = dsl.select(AGENT_SKILL_SLOTS.SKILL_ID)
                .from(AGENT_SKILL_SLOTS)
                .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
                .and(AGENT_SKILL_SLOTS.SLOT_INDEX.eq(slotIndex))
                .fetchOne(AGENT_SKILL_SLOTS.SKILL_ID)
            if (nowOccupiedBy != null && nowOccupiedBy != skill.value) {
                return SkillSlotError.SlotOccupied(slotIndex, SkillId(nowOccupiedBy))
            }
            val nowSlotForSkill = dsl.select(AGENT_SKILL_SLOTS.SLOT_INDEX)
                .from(AGENT_SKILL_SLOTS)
                .where(AGENT_SKILL_SLOTS.AGENT_ID.eq(agent.id))
                .and(AGENT_SKILL_SLOTS.SKILL_ID.eq(skill.value))
                .fetchOne(AGENT_SKILL_SLOTS.SLOT_INDEX)
            if (nowSlotForSkill != null) {
                return SkillSlotError.SkillAlreadySlotted(skill, nowSlotForSkill)
            }
            // Constraint fired but neither lookup explains it — shouldn't happen with
            // our two constraints, but keep the original exception visible to ops.
            throw e
        }
    }

    /**
     * Slot count = `BASE + floor(level / 10)`. Reads the agent's current level from
     * the `agents` row. Returns [BASE_SLOTS] if the agent doesn't exist (defensive —
     * callers always pass a real agent id, but a missing row shouldn't crash a read).
     */
    private fun computeSlotCount(agent: AgentId): Int {
        val level = dsl.select(AGENTS.LEVEL)
            .from(AGENTS)
            .where(AGENTS.ID.eq(agent.id))
            .fetchOne(AGENTS.LEVEL)
            ?: return BASE_SLOTS
        return BASE_SLOTS + (level / SLOTS_PER_LEVEL_GROUP)
    }

    private fun levelFromXp(xp: Int): Int = xp / XP_PER_LEVEL

    private companion object {
        const val BASE_SLOTS = 8
        const val SLOTS_PER_LEVEL_GROUP = 10
        const val XP_PER_LEVEL = 10
        const val RECOMMEND_CAP = 3
        const val RECOMMEND_COOLDOWN_TICKS = 30L
        val MILESTONE_THRESHOLDS = listOf(50, 100, 150)
    }
}
