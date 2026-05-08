package dev.gvart.genesara.player

/**
 * Sum of [PerkEffect.PassiveAura] magnitudes for chosen perks whose owning skill is
 * currently slotted. Reducers add the result as a flat post-scaling term — distinct
 * from [LevelScalingAggregator], which exposes a multiplicative `(1 + bonus)` rate.
 */
interface PassiveAuraAggregator {

    fun bonusFor(agent: AgentId, effect: ScalingEffect): Int

    companion object {
        /** No-op aggregator for reducer tests that don't exercise auras. */
        val NoAura: PassiveAuraAggregator = object : PassiveAuraAggregator {
            override fun bonusFor(agent: AgentId, effect: ScalingEffect): Int = 0
        }
    }
}
