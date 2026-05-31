package dev.gvart.genesara.player

/**
 * Faction rank ladder (mechanics-reference §18). Lives in `:player` rather than
 * `:world` because the per-agent faction rank is denormalized onto
 * `agents.faction_rank` and the skill-slot [slotBonus] (§3) is computed here —
 * `:player` is upstream of `:world` and cannot import `:world` types, so the shared
 * rank enum lives at this level and `:world` imports it downstream. Declaration
 * order is the ladder ([PACT] lowest, [SOVEREIGN] highest).
 */
enum class FactionRank(val slotBonus: Int) {
    PACT(1),
    SPEAKER(2),
    PILLAR(3),
    SOVEREIGN(4);

    fun atLeast(other: FactionRank): Boolean = ordinal >= other.ordinal

    companion object {
        /** Safe parse of a stored column value — null / unknown → null, letting the column outpace the enum. */
        fun parseOrNull(raw: String?): FactionRank? = raw?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}
