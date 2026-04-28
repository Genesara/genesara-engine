package dev.gvart.genesara.player

/**
 * The six primary attributes every agent allocates points into. HP/Stamina/Mana are
 * derived from these via [AttributeDerivation.deriveMaxPools]. Defaults represent the
 * baseline pre-allocation state of a freshly registered agent.
 */
data class AgentAttributes(
    val strength: Int = 1,
    val dexterity: Int = 1,
    val constitution: Int = 1,
    val perception: Int = 1,
    val intelligence: Int = 1,
    val luck: Int = 1,
) {
    operator fun plus(mods: AttributeMods): AgentAttributes = AgentAttributes(
        strength = (strength + mods.strength).coerceAtLeast(MIN_ATTRIBUTE),
        dexterity = (dexterity + mods.dexterity).coerceAtLeast(MIN_ATTRIBUTE),
        constitution = (constitution + mods.constitution).coerceAtLeast(MIN_ATTRIBUTE),
        perception = (perception + mods.perception).coerceAtLeast(MIN_ATTRIBUTE),
        intelligence = (intelligence + mods.intelligence).coerceAtLeast(MIN_ATTRIBUTE),
        luck = (luck + mods.luck).coerceAtLeast(MIN_ATTRIBUTE),
    )

    companion object {
        const val MIN_ATTRIBUTE: Int = 1
        val DEFAULT: AgentAttributes = AgentAttributes()
    }
}

/**
 * Per-race attribute deltas applied on agent registration. Negative values are allowed;
 * the result is clamped to [AgentAttributes.MIN_ATTRIBUTE] so a race never produces a 0.
 */
data class AttributeMods(
    val strength: Int = 0,
    val dexterity: Int = 0,
    val constitution: Int = 0,
    val perception: Int = 0,
    val intelligence: Int = 0,
    val luck: Int = 0,
) {
    companion object {
        val NONE: AttributeMods = AttributeMods()
    }
}
