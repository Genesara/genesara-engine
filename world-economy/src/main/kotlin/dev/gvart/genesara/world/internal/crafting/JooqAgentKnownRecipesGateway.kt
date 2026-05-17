package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLearnSource
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_KNOWN_RECIPES
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqAgentKnownRecipesGateway(
    private val dsl: DSLContext,
) : AgentKnownRecipesGateway {

    @Transactional
    override fun recordIfAbsent(
        agent: AgentId,
        recipe: RecipeId,
        source: RecipeLearnSource,
        sourceRef: String,
        tick: Long,
    ): Boolean {
        val inserted = dsl.insertInto(AGENT_KNOWN_RECIPES)
            .set(AGENT_KNOWN_RECIPES.AGENT_ID, agent.id)
            .set(AGENT_KNOWN_RECIPES.RECIPE_ID, recipe.value)
            .set(AGENT_KNOWN_RECIPES.SOURCE, source.name)
            .set(AGENT_KNOWN_RECIPES.SOURCE_REF, sourceRef)
            .set(AGENT_KNOWN_RECIPES.LEARNED_AT_TICK, tick)
            .onConflictDoNothing()
            .execute()
        return inserted > 0
    }

    @Transactional(readOnly = true)
    override fun known(agent: AgentId): Set<RecipeId> =
        dsl.select(AGENT_KNOWN_RECIPES.RECIPE_ID)
            .from(AGENT_KNOWN_RECIPES)
            .where(AGENT_KNOWN_RECIPES.AGENT_ID.eq(agent.id))
            .fetch(AGENT_KNOWN_RECIPES.RECIPE_ID)
            .filterNotNull()
            .mapTo(mutableSetOf()) { RecipeId(it) }

    @Transactional(readOnly = true)
    override fun isKnown(agent: AgentId, recipe: RecipeId): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(AGENT_KNOWN_RECIPES)
                .where(AGENT_KNOWN_RECIPES.AGENT_ID.eq(agent.id))
                .and(AGENT_KNOWN_RECIPES.RECIPE_ID.eq(recipe.value)),
        )
}
