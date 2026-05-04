package dev.gvart.genesara.world

/**
 * Coarse functional grouping for a [BuildingType]. Lets cross-cutting reducers
 * and future issues (#8 crafting, #18 cultivated resources) discover stations
 * by intent without re-touching the [BuildingType] enum every time a new
 * variant lands.
 *
 * Example: the movement reducer asks `BuildingsLookup.activeStationsAt(node,
 * INFRASTRUCTURE_ROAD)` instead of branching on every concrete road sub-type
 * we may add later (paved road, dirt road, train rail, ...).
 */
enum class BuildingCategoryHint {
    COOKING,
    STORAGE,
    RESIDENCE,
    CRAFTING_STATION_WOOD,
    CRAFTING_STATION_METAL,
    CRAFTING_STATION_POTION,
    AGRICULTURE,
    UTILITY_WATER,
    DEFENSIVE,
    INFRASTRUCTURE_ROAD,
    INFRASTRUCTURE_BRIDGE,
}
