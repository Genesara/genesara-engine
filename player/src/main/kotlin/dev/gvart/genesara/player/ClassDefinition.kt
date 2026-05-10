package dev.gvart.genesara.player

/**
 * Public catalog entry for a base class — the read shape consumers see.
 *
 * String-keyed maps for [damageMultipliers] and [behaviorFingerprint] are
 * deliberate: the keys reference enums owned by `:world` (DamageType,
 * ActionCategory) which `:player` cannot import without flipping module
 * direction. Cross-validation that every key decodes lives next to those enums
 * (see `:world`'s `ClassCatalogConsistencyValidator`).
 */
data class ClassDefinition(
    val id: AgentClass,
    val displayName: String,
    val description: String,
    /** Sight radius in nodes used by [ClassLookup.sightRange]. Must be > 0. */
    val sightRange: Int,
    /** Skills with a 1.5x XP multiplier when slotted. */
    val primarySkills: Set<SkillId>,
    /**
     * Skills with a 1.0x XP multiplier when slotted (no penalty, no bonus).
     * Skills *not* in [primarySkills] or [neutralSkills] accrue XP at 0.5x.
     */
    val neutralSkills: Set<SkillId>,
    /**
     * Combat skills the class cannot wield. The equip reducer rejects with
     * `EquipRejection.CLASS_FORBIDDEN` when the equipped item's `combatSkill`
     * sits in this set. RESEARCHER is the only v1 class with a non-empty list
     * (FIREARMS — design table §14).
     */
    val forbiddenCombatSkills: Set<SkillId>,
    /**
     * Outgoing-damage multiplier per damage type, keyed by `DamageType.name`.
     * Missing keys read as 1.0 at the call site.
     */
    val damageMultipliers: Map<String, Double>,
    /**
     * Per-axis weight used by the level-10 class-choice scoring algorithm
     * (#33) and the L50 evolution scoring algorithm (#34). Keyed by
     * `ActionCategory.name`. Weights are non-negative; missing keys read as 0.0.
     */
    val behaviorFingerprint: Map<String, Double>,
    /**
     * Back-link to the base class for evolution entries (e.g. HEAVY_SOLDIER →
     * SOLDIER). Null on base classes. Set from YAML `parent-class` and
     * cross-validated against the parent's [evolutions] list at startup.
     */
    val parentClass: AgentClass? = null,
    /**
     * Evolution branches available to this class at the L50 event (#34). Empty
     * on evolution entries (single L50 evolution per agent in v1; L100 sub-
     * branches stay deferred). The L50 emitter scores the agent's windowed
     * fingerprint against each entry's [behaviorFingerprint] and offers the
     * top-2.
     */
    val evolutions: List<AgentClass> = emptyList(),
)
