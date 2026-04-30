package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.Rarity

internal data class ItemProperties(
    val displayName: String,
    val description: String = "",
    val category: ItemCategory = ItemCategory.RESOURCE,
    val weightPerUnit: Int = 0,
    val maxStack: Int = Int.MAX_VALUE,
    /**
     * Effect of consuming one unit. `null` for non-consumables — most raw resources
     * (WOOD, STONE, ORE) feed crafting only.
     */
    val consumable: ConsumableEffectProperties? = null,
    /** Whether depleted deposits regenerate. Default true. */
    val regenerating: Boolean = true,
    /** Ticks between regen events. 0 disables regen even if [regenerating] is true. */
    val regenIntervalTicks: Int = 0,
    /** Quantity added per regen interval. */
    val regenAmount: Int = 0,
    /** Skill id (from `:player`'s catalog) trained on a gather. Null for non-gatherables. */
    val gatheringSkill: String? = null,
    /** Default rarity for instances of this item; defaults to [Rarity.COMMON]. */
    val rarity: Rarity = Rarity.COMMON,
    /** Max durability for instances of this item. Null = no durability (stackable). */
    val maxDurability: Int? = null,
    /** Equipment slots an instance of this item can occupy. Empty for non-equipment. */
    val validSlots: Set<EquipSlot> = emptySet(),
    /** Two-handed weapon flag. Locks the off-hand slot when equipped to MAIN_HAND. */
    val twoHanded: Boolean = false,
    /** Attribute floors required to equip; empty when no prerequisites. */
    val requiredAttributes: Map<Attribute, Int> = emptyMap(),
    /** Skill-level floors required to equip; keys are skill ids; empty when none. */
    val requiredSkills: Map<String, Int> = emptyMap(),
)

internal data class ConsumableEffectProperties(
    val gauge: Gauge,
    val amount: Int,
)
