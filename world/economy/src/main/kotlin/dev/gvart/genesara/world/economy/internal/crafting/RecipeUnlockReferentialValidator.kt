package dev.gvart.genesara.world.economy.internal.crafting

import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeUnlockMode
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Fails app boot when a recipe's `unlock-mode` points at a perk or item that
 * isn't in the catalog. Kept separate from [RecipeCatalogValidator] because
 * it crosses module boundaries (perk catalog lives in :player) and because
 * the validator-per-concern split keeps boot-failure messages narrow.
 */
@Component
class RecipeUnlockReferentialValidator(
    private val recipes: RecipeLookup,
    private val perks: PerkLookup,
    private val items: ItemLookup,
) {

    @PostConstruct
    fun validate() {
        val problems = mutableListOf<String>()
        for (recipe in recipes.all()) {
            val rid = recipe.id.value
            when (val mode = recipe.unlockMode) {
                RecipeUnlockMode.Open -> Unit
                is RecipeUnlockMode.ClassPerk -> {
                    if (perks.byId(mode.perk) == null) {
                        problems += "$rid: unlock-mode class-perk '${mode.perk.value}' is not in the perk catalog"
                    }
                }
                is RecipeUnlockMode.ItemLearned -> {
                    if (items.byId(mode.item) == null) {
                        problems += "$rid: unlock-mode item-learned '${mode.item.value}' is not in the items catalog"
                    }
                }
            }
        }
        require(problems.isEmpty()) {
            buildString {
                append("Recipe unlock-mode validation failed:\n")
                problems.forEach { append("  - $it\n") }
            }
        }
    }
}
