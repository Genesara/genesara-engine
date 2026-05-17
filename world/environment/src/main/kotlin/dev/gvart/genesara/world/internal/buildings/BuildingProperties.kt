package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingCategoryHint

data class BuildingProperties(
    val staminaPerStep: Int,
    val hp: Int,
    val categoryHint: BuildingCategoryHint,
    val skillBars: Map<String, BarProperties>,
    val chestCapacityGrams: Int? = null,
    /**
     * Additive contribution to a tile's intermediate sight-blocking height while
     * this building is ACTIVE on the tile. Walls and closed gates raise the bar
     * for whose vision can pass through. GATE entries declare a non-zero value
     * here but only contribute at runtime while CLOSED — the cache recompute
     * factors in the gate state when it sums the per-tile total.
     */
    val sightBlockerHeight: Int = 0,
    /**
     * Additive contribution to the observer's effective height while the agent
     * stands on the building's tile and the building is ACTIVE. Today the only
     * non-zero entry is WATCHTOWER (+1). T2/T3 vantage buildings tune this here.
     */
    val observerHeightBonus: Int = 0,
)
