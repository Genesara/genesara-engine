package dev.gvart.genesara.player

/**
 * A race definition loaded from `player-definition/races.yaml`.
 *
 * - [weight] feeds the random-weighted assignment in `RaceAssigner`. Higher weight = more
 *   common race; weights are relative, not absolute percentages.
 * - [attributeMods] is added to [AgentAttributes.DEFAULT] on registration.
 */
data class Race(
    val id: RaceId,
    val displayName: String,
    val weight: Int,
    val attributeMods: AttributeMods,
    val description: String,
)
