package dev.gvart.genesara.player

/**
 * Sum of `level × perLevelPct` across slotted skills, multiplied per-skill by chosen
 * [PerkEffect.Modifier] perks targeting the same effect. Reducers apply the result as
 * `(1.0 + bonus)` against a base value — `0.25` means +25%.
 */
interface LevelScalingAggregator {

    fun bonusFor(agent: AgentId, effect: ScalingEffect): Double

    companion object {
        /** No-op aggregator for reducer tests that don't exercise scaling. */
        val NoScaling: LevelScalingAggregator = object : LevelScalingAggregator {
            override fun bonusFor(agent: AgentId, effect: ScalingEffect): Double = 0.0
        }
    }
}
