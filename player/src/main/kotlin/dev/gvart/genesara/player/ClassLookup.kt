package dev.gvart.genesara.player

/**
 * Read-only catalog access for the eight base classes. Replaces the narrower
 * `ClassPropertiesLookup` from Phase 0 — the catalog now carries the soft-XP,
 * damage-multiplier, hard-restriction, and behavior-fingerprint surfaces that
 * skill-feature step 6 (#32) wired in.
 */
interface ClassLookup {

    /** Catalog entry for [classId]; null when the YAML is missing the entry. */
    fun byId(classId: AgentClass): ClassDefinition?

    /** All entries in `AgentClass` declaration order. */
    fun all(): List<ClassDefinition>

    /**
     * Sight radius in nodes used by `VisionRadiusImpl`. Returns the catalog
     * default when [classId] is null (pre-level-10 agents).
     */
    fun sightRange(classId: AgentClass?): Int

    /**
     * Skill-XP multiplier for ([classId], [skill]). Primary skills return 1.5,
     * neutral skills 1.0, off-build 0.5. Returns 1.0 when [classId] is null
     * (no class assigned yet — agents at levels 1–9).
     */
    fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double

    /**
     * Outgoing-damage multiplier for ([classId], [damageType]) where
     * [damageType] is a `DamageType.name`. Returns 1.0 when [classId] is null
     * or the catalog declares no override for the type.
     */
    fun damageMultiplier(classId: AgentClass?, damageType: String): Double

    /**
     * True when [classId] hard-bans equipping items mapped to [combatSkill].
     * Returns false when [classId] is null (unclassed agents have no
     * restrictions).
     */
    fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean
}

/**
 * Permissive [ClassLookup] used as a default for callers that don't care about
 * the class system — primarily tests. Mirrors the semantics for an unclassed
 * (pre-level-10) agent: 1.0x XP, 1.0x damage, no hard restrictions, default
 * sight range. Production wiring relies on Spring auto-wiring `ClassLookup` to
 * the real bean; this object is the fallback when Kotlin default-arg expansion
 * fires in a test that bypasses Spring.
 */
object NoOpClassLookup : ClassLookup {
    override fun byId(classId: AgentClass): ClassDefinition? = null
    override fun all(): List<ClassDefinition> = emptyList()
    override fun sightRange(classId: AgentClass?): Int = 3
    override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double = 1.0
    override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
    override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean = false
}
