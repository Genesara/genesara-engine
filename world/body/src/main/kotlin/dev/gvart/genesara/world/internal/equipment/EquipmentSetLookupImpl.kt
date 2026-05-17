package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.EquipmentSetThreshold
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.balance.bindToDomain
import org.springframework.stereotype.Component

@Component
class EquipmentSetLookupImpl(
    props: EquipmentSetDefinitionProperties,
) : EquipmentSetLookup {

    private val byId: Map<EquipmentSetId, EquipmentSet> = props.catalog.entries.associate { (key, properties) ->
        val id = EquipmentSetId(key)
        id to properties.toDomain(id)
    }

    private val byItem: Map<ItemId, List<EquipmentSet>> = byId.values
        .flatMap { set -> set.pieces.map { it to set } }
        .groupBy({ it.first }, { it.second })

    override fun byId(id: EquipmentSetId): EquipmentSet? = byId[id]

    override fun all(): List<EquipmentSet> = byId.values.toList()

    override fun setsContaining(itemId: ItemId): List<EquipmentSet> = byItem[itemId].orEmpty()

    private fun EquipmentSetProperties.toDomain(id: EquipmentSetId): EquipmentSet = EquipmentSet(
        id = id,
        pieces = pieces.map(::ItemId).toSet(),
        thresholds = thresholds.mapValues { (tier, threshold) ->
            EquipmentSetThreshold(
                bonuses = threshold.bonuses.map { it.bindToDomain("${id.value}@$tier") },
                triggeredPassives = threshold.triggeredPassives.map { p ->
                    PerkEffect.TriggeredPassive(
                        trigger = p.trigger,
                        effectKind = p.effectKind,
                        params = p.params,
                        internalCooldownTicks = p.internalCooldownTicks,
                    )
                },
            )
        },
    )
}
