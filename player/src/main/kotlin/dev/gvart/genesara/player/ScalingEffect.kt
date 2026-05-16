package dev.gvart.genesara.player

/**
 * Closed enum of per-level passive scaling outputs. Pinned in
 * `docs/skill-feature-sequence.md` (issue #66) — adding a value implies a new
 * reducer hook.
 */
enum class ScalingEffect {
    SLASH_DAMAGE_BONUS,
    PIERCE_DAMAGE_BONUS,
    BLUNT_DAMAGE_BONUS,
    ENERGY_DAMAGE_BONUS,
    BLOCK_CHANCE,
    DODGE_CHANCE,
    CRIT_CHANCE,
    HARVEST_YIELD_BONUS,
    CRAFT_QUALITY_BONUS,
    LOOT_QUALITY_BONUS,
    MOVEMENT_SPEED,
    STAMINA_REGEN,
    MAX_CARRY_WEIGHT,
    STEALTH_DETECTION_REDUCTION,
    NPC_PERSUASION_BONUS,
    MOUNT_TAMING_BONUS,
    SCAN_RANGE_BONUS,
    /**
     * Raises the trust-gate value threshold for trades — at +N flat bonus, the threshold
     * becomes `base + N`, so an agent with this aura can move more value before the
     * relationship-score check kicks in. Consumed by `TradeReducer` (BARTERING skill).
     */
    TRUST_GATE_VALUE_BONUS,
}
