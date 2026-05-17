package dev.gvart.genesara.world.economy.internal.crafting

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLearnSource
import dev.gvart.genesara.world.RecipeLearning
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class RecipeLearningImpl(
    private val gateway: AgentKnownRecipesGateway,
    private val unlockIndex: RecipeUnlockIndex,
    private val publisher: ApplicationEventPublisher,
) : RecipeLearning {

    override fun learnFromPerk(agent: AgentId, perk: PerkId, tick: Long): List<RecipeId> =
        learn(
            agent = agent,
            recipes = unlockIndex.byPerkId(perk),
            source = RecipeLearnSource.CLASS_PERK,
            sourceRef = perk.value,
            tick = tick,
        )

    override fun learnFromItem(agent: AgentId, item: ItemId, tick: Long): List<RecipeId> =
        learn(
            agent = agent,
            recipes = unlockIndex.byLearningItem(item),
            source = RecipeLearnSource.ITEM_LEARNED,
            sourceRef = item.value,
            tick = tick,
        )

    private fun learn(
        agent: AgentId,
        recipes: List<RecipeId>,
        source: RecipeLearnSource,
        sourceRef: String,
        tick: Long,
    ): List<RecipeId> {
        if (recipes.isEmpty()) return emptyList()
        val learned = mutableListOf<RecipeId>()
        for (recipe in recipes) {
            if (gateway.recordIfAbsent(agent, recipe, source, sourceRef, tick)) {
                learned += recipe
                publisher.publishEvent(
                    AgentEvent.RecipeLearned(
                        agent = agent,
                        recipeId = recipe.value,
                        source = source.name,
                        sourceRef = sourceRef,
                        tick = tick,
                    ),
                )
            }
        }
        return learned
    }
}
