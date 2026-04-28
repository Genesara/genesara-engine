package dev.gvart.genesara.world.internal.balance

internal data class TerrainProperties(
    val displayName: String,
    val traversable: Boolean = true,
    val movementCostMultiplier: Double = .5,
    /**
     * Item ids gatherable from this terrain. Strings (not [dev.gvart.genesara.world.ItemId])
     * because Spring Boot's binder maps YAML lists to `List<String>` natively; the
     * [BalanceLookup] converts them on read.
     */
    val gatherables: List<String> = emptyList(),
)