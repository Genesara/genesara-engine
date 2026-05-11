package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Per-agent persistence for the hybrid recipe-discovery ledger (issue #146).
 *
 * Backed by `world.agent_known_recipes`. Only stores recipes that were
 * actively unlocked through gameplay — `open` recipes are recomputed
 * catalog-side. The ledger never rolls back on de-level, perk-loss, or
 * respec; once learned, the recipe stays known.
 */
interface AgentKnownRecipesGateway {

    /**
     * Insert a ledger row if absent. Returns true when a new row was
     * actually written, false when the (agent, recipe) PK already existed
     * (duplicate consume / replayed perk choice). Used by callers to gate
     * the `RecipeLearned` event emission so duplicates don't double-fire.
     */
    fun recordIfAbsent(
        agent: AgentId,
        recipe: RecipeId,
        source: RecipeLearnSource,
        sourceRef: String,
        tick: Long,
    ): Boolean

    /** Set of recipes [agent] has unlocked through gameplay (may be empty). */
    fun known(agent: AgentId): Set<RecipeId>

    fun isKnown(agent: AgentId, recipe: RecipeId): Boolean = recipe in known(agent)

    companion object {
        /** Empty in-memory stub for tests that don't exercise the ledger. */
        val Empty: AgentKnownRecipesGateway = object : AgentKnownRecipesGateway {
            override fun recordIfAbsent(
                agent: AgentId,
                recipe: RecipeId,
                source: RecipeLearnSource,
                sourceRef: String,
                tick: Long,
            ): Boolean = true

            override fun known(agent: AgentId): Set<RecipeId> = emptySet()
            override fun isKnown(agent: AgentId, recipe: RecipeId): Boolean = false
        }
    }
}

enum class RecipeLearnSource {
    CLASS_PERK,
    ITEM_LEARNED,
}
