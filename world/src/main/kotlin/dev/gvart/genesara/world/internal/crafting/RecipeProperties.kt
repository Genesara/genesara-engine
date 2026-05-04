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
)

internal data class RecipeOutputProperties(
    val item: String,
    val quantity: Int = 1,
)
