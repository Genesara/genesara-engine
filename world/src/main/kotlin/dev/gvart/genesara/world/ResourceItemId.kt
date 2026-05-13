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

    // Cultivated-only outputs (#18) — reached only via FARM_PLOT plot dispatch.
    WHEAT;

    fun toItemId(): ItemId = ItemId(name)
}