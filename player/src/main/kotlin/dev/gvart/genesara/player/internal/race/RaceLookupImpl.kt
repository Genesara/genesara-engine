package dev.gvart.genesara.player.internal.race

import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.Race
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import org.springframework.stereotype.Component

@Component
internal class RaceLookupImpl(
    private val props: RaceDefinitionProperties,
) : RaceLookup {

    private val byId: Map<RaceId, Race> = props.catalog.entries.associate { (key, properties) ->
        val id = RaceId(key)
        id to properties.toRace(id)
    }

    override fun byId(id: RaceId): Race? = byId[id]

    override fun all(): List<Race> = byId.values.toList()

    private fun RaceProperties.toRace(id: RaceId): Race = Race(
        id = id,
        displayName = displayName,
        weight = weight,
        attributeMods = AttributeMods(
            strength = attributeMods.strength,
            dexterity = attributeMods.dexterity,
            constitution = attributeMods.constitution,
            perception = attributeMods.perception,
            intelligence = attributeMods.intelligence,
            luck = attributeMods.luck,
        ),
        description = description,
    )
}
