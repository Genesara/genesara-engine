package dev.gvart.genesara.world

/**
 * Item rarity tier. Catalog items declare a default rarity (almost always [COMMON]
 * for raw resources); per-instance equipment rolls a rarity at craft / loot time
 * that overrides the catalog default. Higher tiers gate higher-tier crafting
 * recipes and unlock perks once the equipment slice lands.
 */
enum class Rarity {
    COMMON,
    UNCOMMON,
    RARE,
    EPIC,
    LEGENDARY,
}
