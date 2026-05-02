package dev.gvart.genesara.world

/**
 * The Tier-1 (non-base) building catalog. Five behavioral types are wired in
 * this slice; the rest are scaffolded with persistent state but their gameplay
 * effect waits on a downstream substrate (cooking + crafting recipes from #8,
 * cultivated resources from #18, PvP combat from Phase 2).
 *
 * The base-tier ladder (T1 outpost → T5 city — see mechanics-reference §13)
 * is a separate concept and lands with the territory work in #23 Phase 3.
 */
enum class BuildingType {
    /** TODO(#8): consult as a cooking station once recipes land; today inert. */
    CAMPFIRE,

    /** Owner-only personal stash backed by `building_chest_inventory`. */
    STORAGE_CHEST,

    /** On the build step that completes the shelter, the builder's safe-node is set to its node. */
    SHELTER,

    /** TODO(#8): consult as a wood crafting station; today inert. */
    WORKBENCH,

    /** TODO(#8): consult as a metal crafting station; today inert. */
    FORGE,

    /** TODO(#8): consult as a potion crafting station; today inert. */
    ALCHEMY_TABLE,

    /** TODO(#18): anchor for plant / tend / harvest verbs; today inert. */
    FARM_PLOT,

    /** Drink succeeds on this node even when terrain is not a water source. */
    WELL,

    /** TODO(#22 Phase 2 PvP): block enemy entry; today inert. */
    WOODEN_WALL,

    /** Halves outgoing-move stamina cost when an agent leaves the road's node. */
    ROAD,

    /** Overrides `TerrainNotTraversable` on the bridge's node. */
    BRIDGE,
}
