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
)

/**
 * The pair of [AgentClass]es offered to the agent at the level-10 event. The
 * declaration order mirrors the scorer's ranking — [first] beats [second] —
 * but the agent may commit either via `select_class`.
 */
data class ClassOffer(val first: AgentClass, val second: AgentClass) {
    init { require(first != second) { "ClassOffer pair must be distinct, got $first twice" } }
    fun contains(classId: AgentClass): Boolean = classId == first || classId == second
    fun toList(): List<AgentClass> = listOf(first, second)
}
