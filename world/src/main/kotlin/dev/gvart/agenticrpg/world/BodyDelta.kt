package dev.gvart.agenticrpg.world

data class BodyDelta(
    val hp: Int = 0,
    val stamina: Int = 0,
    val mana: Int = 0,
) {
    operator fun plus(other: BodyDelta): BodyDelta =
        BodyDelta(hp + other.hp, stamina + other.stamina, mana + other.mana)

    val isZero: Boolean get() = hp == 0 && stamina == 0 && mana == 0
}