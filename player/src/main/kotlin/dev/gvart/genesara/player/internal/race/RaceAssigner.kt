package dev.gvart.genesara.player.internal.race

import dev.gvart.genesara.player.Race
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import org.springframework.stereotype.Component

/**
 * Picks a race for a freshly registered agent. The pick is **weighted** by [Race.weight] so
 * over many registrations, race populations match the YAML-configured distribution.
 *
 * Falls back to [RaceDefinitionProperties.defaultId] when the catalog is empty or all weights
 * are zero — keeps registration robust during local development with sparse seed data.
 */
@Component
internal class RaceAssigner(
    private val races: RaceLookup,
    private val props: RaceDefinitionProperties,
    private val random: RandomSource,
) {

    fun assign(): Race {
        val weighted = races.all().filter { it.weight > 0 }
        if (weighted.isEmpty()) {
            return races.byId(RaceId(props.defaultId))
                ?: error(
                    "Race catalog has no positively-weighted entries and default " +
                        "'${props.defaultId}' is missing — check player-definition/races.yaml."
                )
        }
        val totalWeight = weighted.sumOf { it.weight }
        var roll = random.nextInt(totalWeight)
        for (race in weighted) {
            roll -= race.weight
            if (roll < 0) return race
        }
        // Shouldn't happen mathematically; satisfies the compiler and the boundary case
        // where roll == totalWeight - 1 lands on the final race.
        return weighted.last()
    }
}
