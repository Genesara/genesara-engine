package dev.gvart.genesara.world

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.SkillId

/**
 * An entry from the world's item catalog (`world-definition/items.yaml`).
 *
 * Slice 2 ships only [ItemCategory.RESOURCE] — stackable, no per-instance state.
 * Equipment with rarity / durability / per-instance attributes ships with the
 * equipment slice and uses a different storage shape (separate table). The
 * [rarity] and [maxDurability] catalog fields are populated for *all* items so
 * stackable resources can declare their default rarity (always COMMON in v1)
 * and so equipment items can declare their durability ceiling here without
 * having to also update the per-instance store schema.
 */
data class Item(
    val id: ItemId,
    val displayName: String,
    val description: String,
    val category: ItemCategory,
    /** Weight in grams per single unit. Used for the future Strength-bounded carry cap. */
    val weightPerUnit: Int,
    /** Soft cap on a single inventory stack; harvest can't push a stack above this. */
    val maxStack: Int,
    /**
     * Effect applied when this item is consumed via the `consume` MCP tool. `null` for
     * non-consumables (raw resources used only as crafting inputs).
     */
    val consumable: ConsumableEffect? = null,
    /**
     * If true, depleted node deposits of this item slowly recover toward their initial
     * roll. If false, depletion is permanent (stone, ore, coal, gems, salt).
     *
     * Default true matches the more common case (organic gatherables).
     */
    val regenerating: Boolean = true,
    /**
     * Number of ticks between successive regen events. The lazy-regen logic in
     * [dev.gvart.genesara.world.internal.resources.NodeResourceStore] reads this when
     * computing how many "regen intervals" have elapsed since the last write.
     *
     * `0` disables regen even when [regenerating] is true (a cheap fast-path; tests
     * that don't care about regen can leave both fields at default).
     */
    val regenIntervalTicks: Int = 0,
    /** Quantity added per regen interval, capped at the per-node initial quantity. */
    val regenAmount: Int = 0,
    /**
     * Skill id (from `:player`'s catalog) that a `harvest` of this item trains. Null
     * for non-harvestable items or for resources that aren't tied to a skill (none
     * today). Cross-validated against the skill catalog at startup.
     */
    val gatheringSkill: String? = null,
    /**
     * Default rarity for instances of this item. Stackable resources always emit
     * COMMON; equipment items can declare a higher floor here (e.g. an artifact
     * weapon recipe that always yields RARE). Per-instance rolls (skill + Luck
     * crafting bonuses, loot-table escalation) layer on top of this default and
     * are stored on the equipment-instance row, not in the catalog.
     */
    val rarity: Rarity = Rarity.COMMON,
    /**
     * Max durability for instances of this item. Null for stackable resources
     * (a piece of stone doesn't break — it's consumed wholesale). Set on
     * equipment / tool catalog entries; the per-instance row tracks the live
     * `durabilityCurrent` against this ceiling and the item is destroyed at zero.
     */
    val maxDurability: Int? = null,
    /**
     * Equipment slots an instance of this item can occupy. Empty for
     * non-equipment (resources, consumables) — the equip reducer rejects any
     * attempt to slot an item with no valid slots. Items can be valid in
     * multiple slots (e.g. a generic ring fits both `RING_LEFT` and
     * `RING_RIGHT`); the agent picks which one at equip time.
     */
    val validSlots: Set<EquipSlot> = emptySet(),
    /**
     * If true, this item is a two-handed weapon: it equips into [EquipSlot.MAIN_HAND]
     * and locks [EquipSlot.OFF_HAND] for as long as it stays equipped. Single-hand
     * weapons and shields stay false. Mutually exclusive with `OFF_HAND` being a
     * valid slot for the same item.
     */
    val twoHanded: Boolean = false,
    /**
     * Per-attribute floors an agent must meet to equip an instance of this item.
     * Empty for items without prerequisites. The equip reducer iterates by
     * [Attribute.ordinal] order so the first-failing-attribute reported in the
     * rejection detail is deterministic regardless of the YAML map's insertion
     * order. Note: an agent that *drops* below a requirement (e.g. de-leveling
     * on death) keeps the item equipped — gear is not auto-unequipped on stat
     * changes.
     */
    val requiredAttributes: Map<Attribute, Int> = emptyMap(),
    /**
     * Per-skill level floors an agent must meet to equip an instance of this
     * item. Keys are typed [SkillId] (the YAML binder decodes string keys into
     * `SkillId` at load time). Empty for items without skill prerequisites. A
     * required skill the agent has never trained reads as level 0 and trips
     * the rejection.
     */
    val requiredSkills: Map<SkillId, Int> = emptyMap(),
) {
    init {
        if (twoHanded) {
            require(EquipSlot.OFF_HAND !in validSlots) {
                "${id.value}: two-handed items cannot list OFF_HAND in validSlots"
            }
            require(EquipSlot.MAIN_HAND in validSlots) {
                "${id.value}: two-handed items must include MAIN_HAND in validSlots"
            }
        }
    }
}

enum class ItemCategory {
    RESOURCE,
    /**
     * Anything an agent can wear, wield, or otherwise slot into the 12-slot
     * equipment grid. Per-instance state (rolled rarity, live durability,
     * creator signature) lives in `agent_equipment_instances`, not in the
     * stackable inventory table.
     */
    EQUIPMENT,
}
