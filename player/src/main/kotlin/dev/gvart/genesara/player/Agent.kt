package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId

data class Agent(
    val id: AgentId,
    val owner: PlayerId,
    val name: String,
    val classId: AgentClass? = null,
    val race: RaceId = RaceId("human_commoner"),
    val level: Int = 1,
    val xpCurrent: Int = 0,
    val xpToNext: Int = 100,
    val unspentAttributePoints: Int = 5,
    val attributes: AgentAttributes = AgentAttributes.DEFAULT,
    /**
     * Pending class-choice offer set by the level-10 emitter (#33). Null pre-
     * level-10 and after the agent commits via `select_class`. Always non-null
     * with exactly two distinct entries when present.
     */
    val offeredClasses: ClassOffer? = null,
    /**
     * Pending L50 evolution-choice offer set by the L50 emitter (#34). Null
     * pre-L50, between L10 commit and L50 transition, or after the agent
     * commits via `select_evolution`. Always non-null with exactly two distinct
     * entries when present, and both entries are guaranteed to be evolutions
     * of the agent's current [classId] (the L50 emitter scores against
     * `ClassLookup.evolutionsOf(classId)`).
     */
    val offeredEvolutions: ClassOffer? = null,
    /** Mechanics-reference §19 Authority. */
    val authority: Int = 0,
    /** Mechanics-reference §19 Fame. Below `BalanceLookup.fameWitnessProtectionThreshold` strips PvP protection. */
    val fame: Int = 0,
)

/**
 * The pair of [AgentClass]es offered to the agent at a class milestone — the
 * L10 class choice (#33) and the L50 evolution choice (#34) share the same
 * shape. The declaration order mirrors the scorer's ranking — [first] beats
 * [second] — but the agent may commit either via the matching MCP tool.
 */
data class ClassOffer(val first: AgentClass, val second: AgentClass) {
    init { require(first != second) { "ClassOffer pair must be distinct, got $first twice" } }
    fun contains(classId: AgentClass): Boolean = classId == first || classId == second
    fun toList(): List<AgentClass> = listOf(first, second)
}
