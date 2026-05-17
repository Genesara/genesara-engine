package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingBarView
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingDefView
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import org.springframework.stereotype.Component

@Component
class BuildingsCatalog(
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
        skillBars = skillBars.map {
            BuildingBarView(
                skill = it.skill,
                level = it.level,
                steps = it.steps,
                materialsPerStep = it.materialsPerStep,
            )
        },
        staminaPerStep = staminaPerStep,
        hp = hp,
        categoryHint = categoryHint,
        chestCapacityGrams = chestCapacityGrams,
        sightBlockerHeight = sightBlockerHeight,
        observerHeightBonus = observerHeightBonus,
    )

    private fun toDef(type: BuildingType, props: BuildingProperties): BuildingDef {
        require(props.skillBars.isNotEmpty()) { "Building $type has no skill-bars" }
        val bars = props.skillBars.entries.map { (skillKey, bar) ->
            require(bar.steps > 0) { "Building $type bar $skillKey has non-positive steps ${bar.steps}" }
            BarDefinition(
                skill = SkillId(skillKey),
                level = bar.level,
                steps = bar.steps,
                materialsPerStep = bar.materialsPerStep.entries.associate { (id, qty) -> ItemId(id) to qty },
            )
        }
        return BuildingDef(
            type = type,
            skillBars = bars,
            staminaPerStep = props.staminaPerStep,
            hp = props.hp,
            categoryHint = props.categoryHint,
            chestCapacityGrams = props.chestCapacityGrams,
            sightBlockerHeight = props.sightBlockerHeight,
            observerHeightBonus = props.observerHeightBonus,
        )
    }
}
