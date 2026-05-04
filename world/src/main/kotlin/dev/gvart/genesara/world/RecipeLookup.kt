package dev.gvart.genesara.world

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

data class Recipe(
    val id: RecipeId,
    val output: RecipeOutput,
    val inputs: Map<ItemId, Int>,
    val requiredStation: BuildingCategoryHint,
    val requiredSkill: SkillId,
    val requiredSkillLevel: Int,
    val staminaCost: Int,
)

interface RecipeLookup {
    fun byId(id: RecipeId): Recipe?
    fun all(): List<Recipe>
}
