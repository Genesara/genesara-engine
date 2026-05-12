package dev.gvart.genesara.api.internal.mcp.tools.getrecipes

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.equipment.views.equipmentStatsViewOf
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeUnlockMode
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetRecipesTool(
    private val recipes: RecipeLookup,
    private val items: ItemLookup,
    private val skills: AgentSkillsRegistry,
    private val knownRecipes: AgentKnownRecipesGateway,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_recipes",
        description = "List every recipe this agent currently knows AND can craft (open recipes you have " +
            "the skill for, plus class-perk and item-learned recipes you've unlocked). Read-only — no " +
            "command queued. Hidden recipes don't appear here; learn them via class perks (`select_perk`) " +
            "or by consuming a learnable item.",
    )
    fun invoke(toolContext: ToolContext): GetRecipesResponse {
        touchActivity(toolContext, activity, "get_recipes")
        val agent = AgentContextHolder.current()

        val unlocked = knownRecipes.known(agent)
        val perSkill = skills.snapshot(agent).perSkill
        fun skillLevel(skillId: dev.gvart.genesara.player.SkillId): Int =
            perSkill[skillId]?.level ?: 0

        val visible = recipes.all()
            .filter { recipe ->
                val unlockOk = when (recipe.unlockMode) {
                    RecipeUnlockMode.Open -> true
                    is RecipeUnlockMode.ClassPerk, is RecipeUnlockMode.ItemLearned -> recipe.id in unlocked
                }
                unlockOk && skillLevel(recipe.requiredSkill) >= recipe.requiredSkillLevel
            }
            .sortedBy { it.id.value }
            .map(::project)

        return GetRecipesResponse(recipes = visible)
    }

    private fun project(recipe: Recipe): RecipeView {
        val outputItem = items.byId(recipe.output.item)
        return RecipeView(
            recipeId = recipe.id.value,
            output = RecipeOutputView(
                itemId = recipe.output.item.value,
                name = outputItem?.displayName.orEmpty(),
                description = outputItem?.description.orEmpty(),
                quantity = recipe.output.quantity,
                category = outputItem?.category?.name ?: ItemCategory.RESOURCE.name,
                weightPerUnit = outputItem?.weightPerUnit ?: 0,
                maxStack = outputItem?.maxStack ?: 0,
                consumable = outputItem?.consumable?.let {
                    ConsumableEffectView(gauge = it.gauge.name, amount = it.amount)
                },
                harvestSkill = outputItem?.harvestSkill?.value,
                equipmentStats = outputItem?.let(::equipmentStatsViewOf),
            ),
            inputs = recipe.inputs.entries
                .sortedBy { it.key.value }
                .map { (itemId, qty) ->
                    RecipeInputView(
                        itemId = itemId.value,
                        name = items.byId(itemId)?.displayName.orEmpty(),
                        quantity = qty,
                    )
                },
            requiredStation = recipe.requiredStation.name,
            staminaCost = recipe.staminaCost,
        )
    }
}
