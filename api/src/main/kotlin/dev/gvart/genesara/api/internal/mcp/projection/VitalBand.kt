package dev.gvart.genesara.api.internal.mcp.projection

/**
 * Coarse vitals projection used by every read tool that surfaces an agent's HP / Stamina /
 * Mana or a building's HP. Keeps the band ladder identical across `inspect` (agent vitals)
 * and `look_around` (building HP) so a future tuning of the thresholds doesn't have to
 * track multiple call sites.
 *
 * [zeroLabel] differs by domain: agent body uses "dead", building HP uses "destroyed",
 * future status-effect surfaces may want their own term — the math is the only shared part.
 */
internal fun vitalBand(current: Int, max: Int, zeroLabel: String = "dead"): String = when {
    max <= 0 -> "unknown"
    current <= 0 -> zeroLabel
    current * 10 < max * 3 -> "low"
    current * 10 < max * 7 -> "mid"
    else -> "high"
}
