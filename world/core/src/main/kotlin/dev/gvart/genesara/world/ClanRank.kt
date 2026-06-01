package dev.gvart.genesara.world

/**
 * Clan rank ladder (mechanics-reference §18). Declaration order is the ladder —
 * [Initiate] lowest, [Archon] highest — so [atLeast] / comparisons use [ordinal].
 */
enum class ClanRank {
    INITIATE,
    SWORN,
    BOUND,
    VANGUARD,
    ARCHON;

    fun atLeast(other: ClanRank): Boolean = ordinal >= other.ordinal
}
