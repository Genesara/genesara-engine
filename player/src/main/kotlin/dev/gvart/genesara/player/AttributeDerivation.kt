package dev.gvart.genesara.player

/**
 * Pure function deriving max HP/Stamina/Mana from the six primary attributes.
 *
 * **Formulas are illustrative and tagged TBD in `docs/lore/mechanics-reference.md` Appendix B.**
 * They will be tuned during playtesting. Mana stays as `INT` for now (the design's
 * "null for non-psionic agents" rule lands with the class system in Phase 4).
 */
object AttributeDerivation {

    fun deriveMaxPools(attrs: AgentAttributes): MaxPools = MaxPools(
        maxHp = HP_BASE + attrs.constitution * HP_PER_CON,
        maxStamina = STAMINA_BASE + (attrs.constitution + attrs.dexterity) * STAMINA_PER_PT,
        maxMana = attrs.intelligence * MANA_PER_INT,
    )

    // Tuning constants; flagged TBD in mechanics-reference.md Appendix B.
    private const val HP_BASE = 50
    private const val HP_PER_CON = 10
    private const val STAMINA_BASE = 30
    private const val STAMINA_PER_PT = 5
    private const val MANA_PER_INT = 5
}

data class MaxPools(
    val maxHp: Int,
    val maxStamina: Int,
    val maxMana: Int,
)
