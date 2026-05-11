package dev.gvart.genesara.api.internal.mcp.tools.getrecipes

data class GetRecipesResponse(
    val recipes: List<RecipeView>,
)

data class RecipeView(
    val recipeId: String,
    val output: RecipeOutputView,
    val inputs: List<RecipeInputView>,
    val requiredStation: String,
    val staminaCost: Int,
)

data class RecipeOutputView(
    val itemId: String,
    val name: String,
    val description: String,
    val quantity: Int,
    /**
     * Placeholder for the EquipmentDefinition follow-up (#147). Schema-stable —
     * agents can rely on the field existing across the transition.
     */
    val equipmentStats: Any? = null,
)

data class RecipeInputView(
    val itemId: String,
    val name: String,
    val quantity: Int,
)
