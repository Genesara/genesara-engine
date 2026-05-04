package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import org.springframework.stereotype.Component

@Component
internal class BuildingsCatalog(
    props: BuildingDefinitionProperties,
) : BuildingDefLookup {

    private val byType: Map<BuildingType, BuildingDef> = props.catalog.entries.associate { (key, properties) ->
        val type = BuildingType.valueOf(key)
        type to toDef(type, properties)
    }

    fun def(type: BuildingType): BuildingDef =
        byType[type] ?: error("No building catalog entry for $type")

    fun allDefs(): List<BuildingDef> = byType.values.toList()

    override fun byType(type: BuildingType): BuildingDefView? = byType[type]?.toView()

    override fun all(): List<BuildingDefView> = byType.values.map { it.toView() }

    private fun BuildingDef.toView(): BuildingDefView = BuildingDefView(
        type = type,
        totalMaterials = totalMaterials,
        stepMaterials = stepMaterials,
        requiredSkill = requiredSkill,
        requiredSkillLevel = requiredSkillLevel,
        totalSteps = totalSteps,
        staminaPerStep = staminaPerStep,
        hp = hp,
        categoryHint = categoryHint,
        chestCapacityGrams = chestCapacityGrams,
    )

    private fun toDef(type: BuildingType, props: BuildingProperties): BuildingDef {
        val totalMaterials = props.totalMaterials.entries.associate { (id, qty) -> ItemId(id) to qty }
        return BuildingDef(
            type = type,
            totalMaterials = totalMaterials,
            stepMaterials = computeStepMaterials(totalMaterials, props.totalSteps),
            requiredSkill = SkillId(props.requiredSkill),
            requiredSkillLevel = props.requiredSkillLevel,
            totalSteps = props.totalSteps,
            staminaPerStep = props.staminaPerStep,
            hp = props.hp,
            categoryHint = props.categoryHint,
            chestCapacityGrams = props.chestCapacityGrams,
        )
    }

    private fun computeStepMaterials(
        totalMaterials: Map<ItemId, Int>,
        totalSteps: Int,
    ): List<Map<ItemId, Int>> {
        require(totalSteps > 0) { "totalSteps must be positive, got $totalSteps" }
        val stepsAccumulator = MutableList(totalSteps) { mutableMapOf<ItemId, Int>() }
        for ((item, total) in totalMaterials) {
            val perStep = total / totalSteps
            val remainder = total - perStep * totalSteps
            for (i in 0 until totalSteps - 1) {
                if (perStep > 0) stepsAccumulator[i][item] = perStep
            }
            val tail = perStep + remainder
            if (tail > 0) stepsAccumulator[totalSteps - 1][item] = tail
        }
        return stepsAccumulator.map { it.toMap() }
    }
}
