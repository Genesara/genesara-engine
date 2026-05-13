package dev.gvart.genesara.world

/**
 * Subset of [ItemId] reachable via the `harvest` MCP tool — the union of terrain-spawned
 * resources (`terrains.yaml` `resource-spawns`) and cultivated crop outputs (#18). Refined
 * intermediates (IRON_INGOT, CLOTH, BRICK, ...) and equipment ids stay outside the enum.
 *
 * Used at two boundaries:
 *  - YAML binding for `ResourceSpawnRuleProperties.item`, so a typo in `terrains.yaml`
 *    fails the Spring binder at startup. Cultivated-only entries below MUST NOT appear in
 *    `terrains.yaml` — there's no enforcement today, just a convention.
 *  - The `harvest` MCP tool's `itemId` parameter, so the JSON Schema lists exactly the
 *    valid harvest targets up-front.
 *
 * Inside the engine everything stays keyed by [ItemId]; convert at the boundary via
 * [toItemId].
 */
enum class ResourceItemId {
    WOOD,
    BERRY,
    HERB,
    MUSHROOM,
    FISH,
    HIDE,
    FIBER,
    CLAY,
    PEAT,
    SAND,
    STONE,
    ORE,
    COAL,
    GEM,
    SALT,

    // Cultivars (#18). Spawn rarely on PLAINS / MEADOW / HILLS / FOREST_EDGE so day-0
    // agents can forage a starter unit; the renewable+reliable path is to plant them
    // on a FARM_PLOT and reap many. Plot-first dispatch in HarvestTool prefers the
    // ripe plot over the wild cell when both are present at the agent's node.
    WHEAT,
    POTATO,
    TOMATO,
    PEPPER,
    CORN,
    PUMPKIN;

    fun toItemId(): ItemId = ItemId(name)
}