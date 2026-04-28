package dev.gvart.genesara.world

/**
 * Tick-level change applied to a body. The passive system computes one `BodyDelta` per
 * agent per tick (sum of every passive's contribution) and the apply step clamps to the
 * body's current/max bounds.
 *
 * Survival gauges (`hunger`, `thirst`, `sleep`) move as deltas alongside HP/Stamina/Mana
 * so a single `PassivesApplied` event captures all per-tick body changes.
 */
data class BodyDelta(
    val hp: Int = 0,
    val stamina: Int = 0,
    val mana: Int = 0,
    val hunger: Int = 0,
    val thirst: Int = 0,
    val sleep: Int = 0,
) {
    operator fun plus(other: BodyDelta): BodyDelta = BodyDelta(
        hp + other.hp,
        stamina + other.stamina,
        mana + other.mana,
        hunger + other.hunger,
        thirst + other.thirst,
        sleep + other.sleep,
    )

    val isZero: Boolean
        get() = hp == 0 && stamina == 0 && mana == 0 &&
            hunger == 0 && thirst == 0 && sleep == 0
}
