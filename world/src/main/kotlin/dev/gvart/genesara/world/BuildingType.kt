package dev.gvart.genesara.world

/**
 * The Tier-1/Tier-2 (non-base) building catalog. All entries carry persistent
 * state and a wired gameplay effect except STABLE (waiting on #21 mounts).
 *
 * The base-tier ladder (T1 outpost → T5 city — see mechanics-reference §13)
 * is a separate concept and lands with the territory work in #23 Phase 3.
 */
enum class BuildingType {
    /** Cooking station — gates `craft` recipes whose `requiredStation` is COOKING. */
    CAMPFIRE,

    /** Owner-only personal stash backed by `building_chest_inventory`. */
    STORAGE_CHEST,

    /** On the build step that completes the shelter, the builder's safe-node is set to its node. */
    SHELTER,

    /** Wood crafting station — gates recipes whose `requiredStation` is CRAFTING_STATION_WOOD. */
    WORKBENCH,

    /** Metal crafting station — gates recipes whose `requiredStation` is CRAFTING_STATION_METAL. */
    FORGE,

    /** Potion crafting station — gates recipes whose `requiredStation` is CRAFTING_STATION_POTION. */
    ALCHEMY_TABLE,

    /** Anchor for plant / tend / harvest_crop verbs; the build-complete side-effect inserts an `agent_plots` row. */
    FARM_PLOT,

    /** Drink succeeds on this node even when terrain is not a water source. */
    WELL,

    /** DEFENSIVE: blocks all movement onto its node when active. No friendly bypass; use GATE for permissioned passage. */
    WOODEN_WALL,

    /** Halves move stamina cost in both directions (entering AND leaving a road's node). */
    ROAD,

    /** Overrides `TerrainNotTraversable` on the bridge's node. */
    BRIDGE,

    /** Tier-2: adds a local +2 sight-radius bonus to any agent standing on this node. */
    WATCHTOWER,

    /** Tier-2: doubles the trust-gate value threshold for trades initiated at this node. */
    TRADING_POST,

    /** TODO(#21): mount housing + taming anchor; today inert. */
    STABLE,

    /**
     * DEFENSIVE: like WOODEN_WALL, but passage is gated by per-instance OPEN/CLOSED
     * state. Toggle requires holding a matching GATE_KEY at the gate's node;
     * passage is allowed only while state is OPEN. The builder receives 1 key
     * on completion; copies are minted via `copy_gate_key` at a WORKBENCH.
     */
    GATE,

    /**
     * Extraction infrastructure for `extract`-only resources (COAL, ORE, GOLD).
     * Any agent on the node may extract while the MINE is ACTIVE; security
     * comes from surrounding WALL / GATE structures, not from MINE access
     * control. Terrain-coupled at build time: FOOTHILLS / MOUNTAIN / VOLCANIC.
     */
    MINE,
}
