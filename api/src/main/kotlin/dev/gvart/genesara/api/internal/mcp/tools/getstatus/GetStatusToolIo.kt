package dev.gvart.genesara.api.internal.mcp.tools.getstatus

data class GetStatusResponse(
    val agentId: String,
    val name: String,
    val race: String,
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
    val tick: Long,
    val activeEffects: List<String> = emptyList(),
    val skills: SkillsView,
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