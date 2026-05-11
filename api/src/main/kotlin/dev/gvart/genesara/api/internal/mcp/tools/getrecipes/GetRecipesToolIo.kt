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
    /** `RESOURCE` (stackable) or `EQUIPMENT` (per-instance with rarity/durability). */
    val category: String,
    /** Per-unit weight in grams. Multiplies with [quantity] toward the agent's carry cap. */
    val weightPerUnit: Int,
    /** Soft cap on a single inventory stack; harvest/craft cannot push a stack above this. */
    val maxStack: Int,
    /** Populated when the output item refills a gauge on `consume`. Null for non-consumables. */
    val consumable: ConsumableEffectView? = null,
    /** Skill (if any) trained on a `harvest` or `consume` of the output. Null otherwise. */
    val harvestSkill: String? = null,
    /** Populated for `EQUIPMENT` outputs only. */
    val equipmentStats: EquipmentStatsView? = null,
)

data class ConsumableEffectView(
    val gauge: String,
    val amount: Int,
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
