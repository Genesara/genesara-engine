package dev.gvart.genesara.player.internal.race

import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * Indirection over [Random] so race assignment (and any future weighted picks in this module)
 * can be made deterministic in tests by supplying a seeded or stub implementation.
 *
 * Currently scoped to the `:player` module; revisit and unify if/when other modules need
 * deterministic randomness too (e.g. `randomSpawnableNode()` in `:world`).
 */
internal interface RandomSource {
    fun nextInt(boundExclusive: Int): Int
}

@Component
internal class DefaultRandomSource(
    private val random: Random = Random.Default,
) : RandomSource {
    override fun nextInt(boundExclusive: Int): Int = random.nextInt(boundExclusive)
}
