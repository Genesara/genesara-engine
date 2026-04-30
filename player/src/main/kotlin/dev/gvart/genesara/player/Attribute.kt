package dev.gvart.genesara.player

/**
 * Typed key for the six primary attributes. Mirrors the field names on
 * [AgentAttributes] so YAML catalogs can declare requirements in a
 * non-stringly-typed way (`required-attributes: { STRENGTH: 12 }`) and the
 * equip / future-modifier paths can read the calling agent's value via
 * [valueOn] without a hand-written `when` at every call site.
 */
enum class Attribute {
    STRENGTH,
    DEXTERITY,
    CONSTITUTION,
    PERCEPTION,
    INTELLIGENCE,
    LUCK;

    /** Read this attribute's current value from an [AgentAttributes] snapshot. */
    fun valueOn(attributes: AgentAttributes): Int = when (this) {
        STRENGTH -> attributes.strength
        DEXTERITY -> attributes.dexterity
        CONSTITUTION -> attributes.constitution
        PERCEPTION -> attributes.perception
        INTELLIGENCE -> attributes.intelligence
        LUCK -> attributes.luck
    }
}
