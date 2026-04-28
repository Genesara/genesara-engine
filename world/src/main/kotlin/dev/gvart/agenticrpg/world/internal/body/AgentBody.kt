package dev.gvart.agenticrpg.world.internal.body

import dev.gvart.agenticrpg.player.AgentProfile

internal data class AgentBody(
    val hp: Int,
    val maxHp: Int,
    val stamina: Int,
    val maxStamina: Int,
    val mana: Int,
    val maxMana: Int,
) {
    fun spendStamina(cost: Int): AgentBody =
        copy(stamina = (stamina - cost).coerceAtLeast(0))

    fun regenStamina(amount: Int): AgentBody =
        copy(stamina = (stamina + amount).coerceIn(0, maxStamina))

    fun takeDamage(amount: Int): AgentBody =
        copy(hp = (hp - amount).coerceAtLeast(0))

    companion object {
        fun fromProfile(profile: AgentProfile): AgentBody = AgentBody(
            hp = profile.maxHp, maxHp = profile.maxHp,
            stamina = profile.maxStamina, maxStamina = profile.maxStamina,
            mana = profile.maxMana, maxMana = profile.maxMana,
        )
    }
}