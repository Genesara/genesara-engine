package dev.gvart.genesara.world.internal.body

import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.world.Gauge

internal data class AgentBody(
    val hp: Int,
    val maxHp: Int,
    val stamina: Int,
    val maxStamina: Int,
    val mana: Int,
    val maxMana: Int,
    /**
     * Survival gauge defaults match `fromProfile`'s "fully fed" agent. Explicit defaults
     * keep test fixtures terse — production paths (registrar, repo load) always pass
     * concrete values, so defaults here can only show up when a test omits them.
     */
    val hunger: Int = DEFAULT_MAX_HUNGER,
    val maxHunger: Int = DEFAULT_MAX_HUNGER,
    val thirst: Int = DEFAULT_MAX_THIRST,
    val maxThirst: Int = DEFAULT_MAX_THIRST,
    val sleep: Int = DEFAULT_MAX_SLEEP,
    val maxSleep: Int = DEFAULT_MAX_SLEEP,
) {
    fun spendStamina(cost: Int): AgentBody =
        copy(stamina = (stamina - cost).coerceAtLeast(0))

    fun regenStamina(amount: Int): AgentBody =
        copy(stamina = (stamina + amount).coerceIn(0, maxStamina))

    fun takeDamage(amount: Int): AgentBody =
        copy(hp = (hp - amount).coerceAtLeast(0))

    /** Refill the named gauge by [amount]; clamped to its max. */
    fun refill(gauge: Gauge, amount: Int): AgentBody = when (gauge) {
        Gauge.HUNGER -> copy(hunger = (hunger + amount).coerceIn(0, maxHunger))
        Gauge.THIRST -> copy(thirst = (thirst + amount).coerceIn(0, maxThirst))
        Gauge.SLEEP -> copy(sleep = (sleep + amount).coerceIn(0, maxSleep))
    }

    /** Current value of [gauge]. Convenience for passive logic that branches on gauges. */
    fun valueOf(gauge: Gauge): Int = when (gauge) {
        Gauge.HUNGER -> hunger
        Gauge.THIRST -> thirst
        Gauge.SLEEP -> sleep
    }

    /**
     * True if any survival gauge is at or below its per-gauge low threshold (i.e. the
     * body is too hungry / thirsty / fatigued to recover normally). The caller supplies
     * the threshold lookup so per-gauge tuning lives in the balance layer, not here —
     * `BalanceLookup.gaugeLowThreshold` already advertises a per-gauge contract.
     */
    fun isVitalsLow(lowThreshold: (Gauge) -> Int): Boolean =
        Gauge.entries.any { gauge -> valueOf(gauge) <= lowThreshold(gauge) }

    /** Hunger AND thirst at-or-above the buff threshold. Sleep excluded — its high-end is offline regen, not a stamina buff. */
    fun isVitalsHigh(buffThreshold: (Gauge) -> Int): Boolean =
        hunger >= buffThreshold(Gauge.HUNGER) && thirst >= buffThreshold(Gauge.THIRST)

    /** True if any survival gauge has hit zero — body is starving and takes damage. */
    fun isStarving(): Boolean = hunger == 0 || thirst == 0 || sleep == 0

    companion object {
        /**
         * Default starting maxima for the survival gauges. Hardcoded here for slice 3;
         * the design ties these to Constitution and lands when the equipment slice
         * introduces the derivation.
         */
        const val DEFAULT_MAX_HUNGER = 100
        const val DEFAULT_MAX_THIRST = 100
        const val DEFAULT_MAX_SLEEP = 100

        fun fromProfile(profile: AgentProfile): AgentBody = AgentBody(
            hp = profile.maxHp, maxHp = profile.maxHp,
            stamina = profile.maxStamina, maxStamina = profile.maxStamina,
            mana = profile.maxMana, maxMana = profile.maxMana,
            hunger = DEFAULT_MAX_HUNGER, maxHunger = DEFAULT_MAX_HUNGER,
            thirst = DEFAULT_MAX_THIRST, maxThirst = DEFAULT_MAX_THIRST,
            sleep = DEFAULT_MAX_SLEEP, maxSleep = DEFAULT_MAX_SLEEP,
        )
    }
}
