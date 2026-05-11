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
     * Populated for equipment outputs (#147). Null for stackable resources
     * and for equipment that grants no bonuses and has no requirements.
     */
    val equipmentStats: EquipmentStatsView? = null,
)

data class RecipeInputView(
    val itemId: String,
    val name: String,
    val quantity: Int,
)

data class EquipmentStatsView(
    val slots: List<String>,
    val twoHanded: Boolean,
    val maxDurability: Int?,
    val damageType: String?,
    val weaponPower: Int?,
    val range: Int?,
    val combatSkill: String?,
    val requiredAttributes: Map<String, Int>,
    val requiredSkills: Map<String, Int>,
    val bonuses: List<EquipmentBonusView>,
)

data class EquipmentBonusView(
    val target: String,
    val magnitude: Int,
)
