package dev.gvart.genesara.world.economy.internal.cultivation

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.Crop
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.ItemId
import org.springframework.stereotype.Component

@Component
class CropLookupImpl(
    props: CropDefinitionProperties,
) : CropLookup {

    private val byId: Map<CropId, Crop> = props.catalog.entries.associate { (key, properties) ->
        val id = CropId(key)
        id to properties.toCrop(id)
    }

    override fun byId(id: CropId): Crop? = byId[id]

    override fun all(): List<Crop> = byId.values.toList()

    private fun CropProperties.toCrop(id: CropId): Crop {
        require(ticksToRipe > 0) { "$id: ticksToRipe must be positive" }
        require(neglectWindowTicks > 0) { "$id: neglectWindowTicks must be positive" }
        require(baseYield > 0) { "$id: baseYield must be positive" }
        require(maxLuckBonus >= 0) { "$id: maxLuckBonus must be non-negative" }
        require(gainPerLevel >= 0.0) { "$id: gainPerLevel must be non-negative" }
        require(requiredTerrain.isNotEmpty()) { "$id: requiredTerrain must not be empty" }
        require(staminaCostPlant >= 0 && staminaCostTend >= 0 && staminaCostHarvest >= 0) {
            "$id: stamina costs must be non-negative"
        }
        return Crop(
            id = id,
            seedItem = ItemId(seedItem),
            ticksToRipe = ticksToRipe,
            outputItem = ItemId(outputItem),
            baseYield = baseYield,
            neglectWindowTicks = neglectWindowTicks,
            requiredTerrain = requiredTerrain.toSet(),
            requiredFarmingLevel = requiredFarmingLevel,
            gainPerLevel = gainPerLevel,
            maxLuckBonus = maxLuckBonus,
            staminaCostPlant = staminaCostPlant,
            staminaCostTend = staminaCostTend,
            staminaCostHarvest = staminaCostHarvest,
            farmingSkill = FARMING_SKILL,
        )
    }

    companion object {
        // Single-skill cultivation in v1 — moves to YAML if multi-skill cultivation lands.
        internal val FARMING_SKILL: SkillId = SkillId("FARMING")
    }
}
