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
)

internal data class ConsumableEffectProperties(
    val gauge: Gauge,
    val amount: Int,
)
