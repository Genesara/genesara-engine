package dev.gvart.genesara.api.internal.mcp.tools.getstatus

import dev.gvart.genesara.player.AgentClass

data class GetStatusResponse(
    val agentId: String,
    val name: String,
    val race: String,
    /** Class committed via `select_class`; null pre-level-10 or while a pick is pending. */
    val classId: AgentClass? = null,
    val level: Int,
    val xp: XpView,
    val attributes: AttributesView,
    val unspentAttributePoints: Int,
    val hp: PoolView,
    val stamina: PoolView,
    val mana: PoolView,
    val hunger: PoolView,
    val thirst: PoolView,
    val sleep: PoolView,
    val location: Long?,
    /** Node id of the agent's currently-bound safe node, or `null` if none is set. */
    val safeNode: Long? = null,
    val tick: Long,
    val activeEffects: List<String> = emptyList(),
    val skills: SkillsView,
    /**
     * The two classes offered by the level-10 event when [classId] is null.
     * Empty list when no offer is pending (pre-level-10 or already classed). The agent
     * commits one via `select_class`. The list mirrors the scorer's ranking — the
     * first entry is the strongest fingerprint match — but either is a legal pick.
     */
    val pendingClassChoice: List<AgentClass> = emptyList(),
    /**
     * The two evolutions offered by the level-50 event when the agent is on a
     * base class. Empty list when no evolution offer is pending (pre-L50,
     * mid-L50-pending, or already evolved). The agent commits one via
     * `select_evolution`. The list mirrors the scorer's ranking — first entry
     * is the strongest fingerprint match — but either is a legal pick.
     */
    val pendingEvolutionChoice: List<AgentClass> = emptyList(),
)

data class XpView(
    val current: Int,
    val toNext: Int,
)

data class AttributesView(
    val strength: Int,
    val dexterity: Int,
    val constitution: Int,
    val perception: Int,
    val intelligence: Int,
    val luck: Int,
)

data class PoolView(
    val current: Int,
    val max: Int,
)

data class SkillsView(
    val slotCount: Int,
    val slotsFilled: Int,
    /** One entry per slot, length == [slotCount], ordered by slot index 0..slotCount-1. */
    val slots: List<SkillSlotView>,
    /** Discovered skills the agent has not placed in a permanent slot yet. */
    val unslotted: List<SkillEntryView>,
    /** Perks already committed via `select_perk`. Ordered by skill, then milestone. */
    val chosenPerks: List<ChosenPerkView> = emptyList(),
    /** Milestones the agent has reached on a slotted skill but not yet committed a pick for. */
    val pendingPerkChoices: List<PendingPerkChoiceView> = emptyList(),
)

data class SkillSlotView(
    val slotIndex: Int,
    /** null when this slot is empty. */
    val skill: SkillEntryView?,
)

data class SkillEntryView(
    val id: String,
    val displayName: String,
    val category: String,
    val xp: Int,
    val level: Int,
    val recommendCount: Int,
)

data class ChosenPerkView(
    val skillId: String,
    val milestone: Int,
    val perkId: String,
)

data class PendingPerkChoiceView(
    val skillId: String,
    val milestone: Int,
    val options: List<String>,
)