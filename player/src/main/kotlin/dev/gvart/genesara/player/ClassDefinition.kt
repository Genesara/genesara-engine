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
     * (#33). Keyed by `ActionCategory.name`. Weights are non-negative; missing
     * keys read as 0.0.
     *
     * TODO(level-10-event): unread by reducers in this slice — populated in
     * step 6 (#32) so #33's scoring algorithm can ship without a YAML
     * migration. Wire the reader when the level-10 event reducer lands.
     */
    val behaviorFingerprint: Map<String, Double>,
)
