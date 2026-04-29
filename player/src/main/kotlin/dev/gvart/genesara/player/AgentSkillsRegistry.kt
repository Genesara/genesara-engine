package dev.gvart.genesara.player

/**
 * Read + write surface for per-agent skill state — the XP ledger, the slot
 * assignments, and the recommendation counter. Backed by Postgres
 * (`JooqAgentSkillsRegistry`).
 *
 * Design contract (project_skills_design.md):
 * - Agents start with no slots filled; there is no default-slot seeding.
 * - **XP only accrues for skills currently in a slot.** [addXpIfSlotted] is the
 *   single canonical write path — there is no unconditional `addXp`.
 * - **Slots are permanent.** [setSlot] is INSERT-only: it rejects if the target
 *   slot is occupied or the skill is already in another slot. There is no
 *   `clearSlot` operation, and no callers should add one without explicit design
 *   sign-off.
 * - **Recommendations** are gated by a per-skill cooldown and a per-skill cap of
 *   3. [maybeRecommend] encapsulates the entire decision and side-effects when
 *   the decision is "yes".
 */
interface AgentSkillsRegistry {

    /**
     * Full per-agent skill snapshot, including catalog skills the agent has never
     * touched. Slot assignments and recommendation counts are joined in.
     */
    fun snapshot(agent: AgentId): AgentSkillsSnapshot

    /**
     * Add [delta] XP to the (agent, skill) pair **only if** [skill] is currently
     * in one of the agent's slots. The result discriminates between "skill not
     * slotted, nothing accrued" and "XP accrued, here are the crossed milestone
     * thresholds" — callers should route the unslotted case directly to
     * [maybeRecommend] rather than re-querying the slot table.
     */
    fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult

    /**
     * Decide whether to fire a `SkillRecommended` event for (agent, skill) at
     * [tick]. Skips and returns null when **any** of the following hold:
     *  - The skill is already slotted (no need to recommend).
     *  - The agent's slots are all filled (nowhere to put a recommended skill).
     *  - The per-skill recommendation count is already at the cap (3).
     *  - The cooldown since the last recommendation has not elapsed.
     *
     * On a "yes" result, increments `recommend_count`, stamps
     * `last_recommended_at_tick`, and returns the new count (1..3).
     */
    fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int?

    /**
     * Place [skill] permanently into [slotIndex]. Validates:
     *  - [slotIndex] is within the agent's computed slot count.
     *  - [skill] exists in the catalog (caller's responsibility — registry trusts).
     *  - The target slot is empty.
     *  - [skill] is not already in another slot.
     *
     * On any rejection returns the matching [SkillSlotError]; on success writes
     * the row and returns null.
     */
    fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError?
}

/** Per-agent skill state, keyed by every catalog skill the agent has touched. */
data class AgentSkillsSnapshot(
    val perSkill: Map<SkillId, AgentSkillState>,
    val slotCount: Int,
    val slotsFilled: Int,
)

data class AgentSkillState(
    val skill: SkillId,
    val xp: Int,
    val level: Int,
    /** Slot index (0-based) the skill is permanently placed in, or null. */
    val slotIndex: Int?,
    /** How many recommendation events have been fired for this (agent, skill). 0..3. */
    val recommendCount: Int,
)

/**
 * Outcome of an [AgentSkillsRegistry.addXpIfSlotted] call.
 *
 * Returning a sealed type (rather than `List<Int>` with empty == "didn't accrue")
 * keeps the caller's branching unambiguous — the caller can route to
 * [AgentSkillsRegistry.maybeRecommend] only when the skill was genuinely unslotted,
 * avoiding a redundant slot lookup on the hot path.
 */
sealed interface AddXpResult {
    /** [skill] is not in any slot for this agent; XP did not accrue. */
    data object Unslotted : AddXpResult

    /**
     * XP accrued. [crossedMilestones] is the list of thresholds (50, 100, 150)
     * that were strictly crossed by this addition; empty when no milestone was
     * crossed but XP did still accrue.
     */
    data class Accrued(val crossedMilestones: List<Int>) : AddXpResult
}

/** Why a [AgentSkillsRegistry.setSlot] call was rejected. */
sealed interface SkillSlotError {
    data class SlotIndexOutOfRange(val slotIndex: Int, val slotCount: Int) : SkillSlotError
    data class SlotOccupied(val slotIndex: Int, val occupiedBy: SkillId) : SkillSlotError
    data class SkillAlreadySlotted(val skill: SkillId, val existingSlotIndex: Int) : SkillSlotError
}
