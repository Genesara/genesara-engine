package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.RecipeUnlockMode
import org.springframework.stereotype.Component

@Component
internal class RecipeLookupImpl(
    props: RecipeDefinitionProperties,
) : RecipeLookup {

    private val byId: Map<RecipeId, Recipe> = props.catalog.entries.associate { (key, properties) ->
        val id = RecipeId(key)
        id to properties.toRecipe(id)
    }

    override fun byId(id: RecipeId): Recipe? = byId[id]

    override fun all(): List<Recipe> = byId.values.toList()

    private fun RecipeProperties.toRecipe(id: RecipeId): Recipe = Recipe(
        id = id,
        output = RecipeOutput(
            item = ItemId(output.item),
            quantity = output.quantity,
        ),
        inputs = inputs.entries.associate { (item, qty) -> ItemId(item) to qty },
        requiredStation = requiredStation,
        requiredSkill = SkillId(requiredSkill),
        requiredSkillLevel = requiredSkillLevel,
        staminaCost = staminaCost,
        unlockMode = unlockMode.toDomain(id),
        requiresSource = requiresSource?.let(::ItemId),
    )

    private fun RecipeUnlockModeProperties?.toDomain(id: RecipeId): RecipeUnlockMode {
        if (this == null) return RecipeUnlockMode.Open
        val explicitOpen = open == true
        val flags = listOfNotNull(
            "open".takeIf { explicitOpen },
            "class-perk".takeIf { classPerk != null },
            "item-learned".takeIf { itemLearned != null },
        )
        require(flags.size <= 1) {
            "$id: unlock-mode must specify at most one of open / class-perk / item-learned (got $flags)"
        }
        return when {
            classPerk != null -> RecipeUnlockMode.ClassPerk(PerkId(classPerk))
            itemLearned != null -> RecipeUnlockMode.ItemLearned(ItemId(itemLearned))
            else -> RecipeUnlockMode.Open
        }
    }
}
