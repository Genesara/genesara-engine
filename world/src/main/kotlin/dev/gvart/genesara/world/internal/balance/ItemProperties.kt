package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.ItemCategory

internal data class ItemProperties(
    val displayName: String,
    val description: String = "",
    val category: ItemCategory = ItemCategory.RESOURCE,
    val weightPerUnit: Int = 0,
    val maxStack: Int = Int.MAX_VALUE,
)
