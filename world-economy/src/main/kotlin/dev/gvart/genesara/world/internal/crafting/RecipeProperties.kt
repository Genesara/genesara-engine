package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.world.BuildingCategoryHint

/**
 * Spring `@ConfigurationProperties` binding intermediate. Catalog ids stay stringly
 * typed here — the same pattern as [dev.gvart.genesara.world.internal.balance.ItemProperties]
 * and [dev.gvart.genesara.world.internal.buildings.BuildingProperties] — and are
 * converted to [dev.gvart.genesara.world.ItemId] / [dev.gvart.genesara.player.SkillId]
 * at the [RecipeLookupImpl] boundary. Spring Boot's binder doesn't handle Kotlin
 * `@JvmInline value class` keys without a custom converter wiring that the existing
 * binding layers don't already use.
 */
internal data class RecipeProperties(
    val output: RecipeOutputProperties,
    val inputs: Map<String, Int> = emptyMap(),
    val requiredStation: BuildingCategoryHint,
    val requiredSkill: String,
    val requiredSkillLevel: Int = 0,
    val staminaCost: Int,
    val unlockMode: RecipeUnlockModeProperties? = null,
    /**
     * String item-id required as the `craft` command's `source` instance.
     * Mapped to [dev.gvart.genesara.world.Recipe.requiresSource] at catalog
     * load; null = recipe does not consume a per-instance template.
     */
    val requiresSource: String? = null,
)

internal data class RecipeOutputProperties(
    val item: String,
    val quantity: Int = 1,
)

/**
 * YAML shape for `unlock-mode`. Exactly one of [classPerk] / [itemLearned]
 * is set; both null (or the whole block absent) means [open]. The binder
 * itself can't enforce mutual exclusion — [RecipeLookupImpl] does.
 */
internal data class RecipeUnlockModeProperties(
    val open: Boolean? = null,
    val classPerk: String? = null,
    val itemLearned: String? = null,
)
