package dev.gvart.genesara.world.economy.internal.crafting

import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeUnlockMode
import org.springframework.stereotype.Component

/**
 * Reverse lookup over the recipe catalog: "given a perk / item the agent
 * just earned, which recipes does it teach?". Built once at startup; the
 * recipe catalog is immutable for the lifetime of the process.
 */
@Component
internal class RecipeUnlockIndex(
    recipes: RecipeLookup,
) {

    private val byPerk: Map<PerkId, List<RecipeId>> =
        recipes.all()
            .mapNotNull { recipe ->
                (recipe.unlockMode as? RecipeUnlockMode.ClassPerk)?.let { it.perk to recipe.id }
            }
            .groupBy({ it.first }, { it.second })

    private val byLearningItem: Map<ItemId, List<RecipeId>> =
        recipes.all()
            .mapNotNull { recipe ->
                (recipe.unlockMode as? RecipeUnlockMode.ItemLearned)?.let { it.item to recipe.id }
            }
            .groupBy({ it.first }, { it.second })

    fun byPerkId(perk: PerkId): List<RecipeId> = byPerk[perk].orEmpty()

    fun byLearningItem(item: ItemId): List<RecipeId> = byLearningItem[item].orEmpty()
}
