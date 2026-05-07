package dev.gvart.genesara.world

/**
 * Subset of [ItemId] covering raw, gatherable resources — the things that appear in
 * `terrains.yaml` `resource-spawns` and that an agent reaches via `harvest`. Refined
 * intermediates (IRON_INGOT, CLOTH, BRICK, ...) and equipment ids stay outside the
 * enum; they're never spawned on terrain and never targeted by `harvest`.
 *
 * Used at two boundaries:
 *  - YAML binding for `ResourceSpawnRuleProperties.item`, so a typo in `terrains.yaml`
 *    fails the Spring binder at startup.
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
    SALT;

    fun toItemId(): ItemId = ItemId(name)
}