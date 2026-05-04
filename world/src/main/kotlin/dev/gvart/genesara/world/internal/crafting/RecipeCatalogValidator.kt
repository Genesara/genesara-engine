package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/** Fail-fast cross-check of the recipe catalog at startup. */
@Component
internal class RecipeCatalogValidator(
    private val recipes: RecipeLookup,
    private val items: ItemLookup,
    private val skills: SkillLookup,
    private val buildings: BuildingsCatalog,
) {

    @PostConstruct
    fun validate() {
        val problems = mutableListOf<String>()
        val stationsWithVariant: Set<BuildingCategoryHint> =
            buildings.allDefs().map { it.categoryHint }.toSet()

        for (recipe in recipes.all()) {
            val rid = recipe.id.value

            if (recipe.output.quantity <= 0) {
                problems += "$rid: output quantity must be > 0 (got ${recipe.output.quantity})"
            }
            if (recipe.staminaCost <= 0) {
                problems += "$rid: stamina-cost must be > 0 (got ${recipe.staminaCost})"
            }
            if (recipe.requiredSkillLevel < 0) {
                problems += "$rid: required-skill-level must be >= 0 (got ${recipe.requiredSkillLevel})"
            }
            val outputItem = items.byId(recipe.output.item)
            if (outputItem == null) {
                problems += "$rid: output item '${recipe.output.item.value}' is not in the items catalog"
            } else {
                if (outputItem.category == ItemCategory.EQUIPMENT && outputItem.maxDurability == null) {
                    // Reducer would crash with `error()` rather than reject — the catalog must
                    // never let an EQUIPMENT-output recipe reach the reducer with no durability.
                    problems += "$rid: equipment output ${outputItem.id.value} has no max-durability"
                }
                if (outputItem.category == ItemCategory.RESOURCE && recipe.output.quantity > outputItem.maxStack) {
                    problems += "$rid: output quantity ${recipe.output.quantity} exceeds ${outputItem.id.value}.maxStack ${outputItem.maxStack}"
                }
            }
            var inputGrams = 0
            for ((item, qty) in recipe.inputs) {
                if (qty <= 0) {
                    problems += "$rid: input ${item.value} quantity must be > 0 (got $qty)"
                }
                val inputItem = items.byId(item)
                if (inputItem == null) {
                    problems += "$rid: input item '${item.value}' is not in the items catalog"
                } else {
                    if (inputItem.category != ItemCategory.RESOURCE) {
                        problems += "$rid: input ${item.value} must be RESOURCE category (got ${inputItem.category})"
                    }
                    inputGrams += inputItem.weightPerUnit * qty
                }
            }
            if (outputItem != null && outputItem.category == ItemCategory.RESOURCE) {
                val outputGrams = outputItem.weightPerUnit * recipe.output.quantity
                if (outputGrams > inputGrams) {
                    // Stackable craft skips carry-cap; the invariant holds the asymmetry safe.
                    problems += "$rid: stackable output weight ($outputGrams g) exceeds consumed inputs ($inputGrams g)"
                }
            }
            if (skills.byId(recipe.requiredSkill) == null) {
                problems += "$rid: required-skill '${recipe.requiredSkill.value}' is not in the skill catalog"
            }
            if (recipe.requiredStation !in stationsWithVariant) {
                problems += "$rid: required-station ${recipe.requiredStation.name} has no building variant in the catalog"
            }
        }

        require(problems.isEmpty()) {
            buildString {
                append("Recipe catalog validation failed:\n")
                problems.forEach { append("  - $it\n") }
            }
        }
    }
}
