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
    /**
     * Some recipes operate on an existing per-instance item (template-style):
     * GATE_KEY_COPY consumes IRON_INGOT + an existing GATE_KEY, mints a new
     * key bound to the same gate as the source. Future upgrade recipes will
     * carry an existing EquipmentInstance for refinement. When non-null the
     * `craft` command must supply `source` (the instance UUID); the reducer
     * validates the source resolves to an owned instance of the named item
     * type before consuming it (or not — keys are templates and stay).
     */
    val requiresSource: ItemId? = null,
)

interface RecipeLookup {
    fun byId(id: RecipeId): Recipe?
    fun all(): List<Recipe>
}
