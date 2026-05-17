package dev.gvart.genesara.world.internal.behavior

/**
 * The 8-axis behavior fingerprint scored against per-class fingerprints at the
 * level-10 event (#33) and L50 evolution (#34). **Internal by design** — the
 * counters this enum keys must never surface through MCP tools or projections.
 */
internal enum class ActionCategory {
    COMBAT,
    GATHER,
    CRAFT,
    BUILD,
    // TODO(verbs): SOCIAL/MEDICAL/TRADE remain unmapped to v1 verbs — wire
    // when say/heal_other/trade reducers land.
    SOCIAL,
    EXPLORE,
    MEDICAL,
    TRADE,
}
