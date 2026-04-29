package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemCategory

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
)

internal data class ConsumableEffectProperties(
    val gauge: Gauge,
    val amount: Int,
)
