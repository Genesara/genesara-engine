package dev.gvart.genesara.player.internal.balance

/**
 * YAML-binding shape for a single class entry in `player-definition/classes.yaml`.
 * Translation to the public [dev.gvart.genesara.player.ClassDefinition] happens in
 * [ClassDefinitionLookup] after [ClassValidator] has run.
 *
 * String-typed lists are parsed into `SkillId`s in the lookup; raw strings here
 * keep YAML binding boring and let the validator surface a single, readable
 * "skill X is unknown" message instead of a binder failure.
 */
internal data class ClassProperties(
    val displayName: String = "",
    val description: String = "",
    val sightRange: Int = 3,
    val primarySkills: List<String> = emptyList(),
    val neutralSkills: List<String> = emptyList(),
    val forbiddenCombatSkills: List<String> = emptyList(),
    val damageMultipliers: Map<String, Double> = emptyMap(),
    val behaviorFingerprint: Map<String, Double> = emptyMap(),
    /** Back-link from an evolution to its base class. Null on base classes. */
    val parentClass: dev.gvart.genesara.player.AgentClass? = null,
    /** Forward-link from a base class to its L50 evolution branches. Empty on evolutions. */
    val evolutions: List<dev.gvart.genesara.player.AgentClass> = emptyList(),
)
