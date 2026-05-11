package dev.gvart.genesara.world

import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId

@JvmInline
value class RecipeId(val value: String) {
    init {
        require(value.isNotBlank()) { "RecipeId must not be blank" }
    }

    override fun toString(): String = value
}

data class RecipeOutput(
    val item: ItemId,
    val quantity: Int,
)

/**
 * How a recipe becomes visible to an agent. The skill-level gate is separate
 * — it layers on top of every mode and gates craftability, not visibility on
 * its own.
 */
sealed interface RecipeUnlockMode {
    data object Open : RecipeUnlockMode
    data class ClassPerk(val perk: PerkId) : RecipeUnlockMode
    data class ItemLearned(val item: ItemId) : RecipeUnlockMode
}

data class Recipe(
    val id: RecipeId,
    val output: RecipeOutput,
    val inputs: Map<ItemId, Int>,
    val requiredStation: BuildingCategoryHint,
    val requiredSkill: SkillId,
    val requiredSkillLevel: Int,
    val staminaCost: Int,
    val unlockMode: RecipeUnlockMode = RecipeUnlockMode.Open,
)

interface RecipeLookup {
    fun byId(id: RecipeId): Recipe?
    fun all(): List<Recipe>
}
