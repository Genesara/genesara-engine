package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.world.Rarity
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * Per-craft rarity roll for the equipment branch of [CraftReducer].
 *
 * Formula:
 * ```
 * baseTier   = (skillLevel / SKILL_PER_TIER).coerceIn(0, MAX_TIER)
 * firstBump  = if (rng.nextInt(100) < luck * FIRST_BUMP_LUCK_MULTIPLIER) 1 else 0
 * secondBump = if (firstBump > 0 && rng.nextInt(100) < luck) 1 else 0
 * final      = (baseTier + firstBump + secondBump).coerceAtMost(MAX_TIER)
 * ```
 *
 * Constructor-injected [Random] so tests pass a seeded instance and pin tier
 * outcomes. The default Spring bean uses [Random.Default].
 */
@Component
internal open class RarityRoller(
    private val rng: Random = Random.Default,
) {

    open fun roll(skillLevel: Int, luck: Int): Rarity {
        val baseTier = (skillLevel / SKILL_PER_TIER).coerceIn(0, MAX_TIER)
        val firstBump = if (rng.nextInt(100) < luck * FIRST_BUMP_LUCK_MULTIPLIER) 1 else 0
        val secondBump = if (firstBump > 0 && rng.nextInt(100) < luck) 1 else 0
        val finalTier = (baseTier + firstBump + secondBump).coerceAtMost(MAX_TIER)
        return TIERS[finalTier]
    }

    private companion object {
        const val SKILL_PER_TIER = 10
        const val FIRST_BUMP_LUCK_MULTIPLIER = 2
        val TIERS = Rarity.entries.toList()
        val MAX_TIER = TIERS.size - 1
    }
}
