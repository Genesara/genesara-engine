package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.ConsumableEffect
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import org.springframework.stereotype.Component

@Component
internal class ItemLookupImpl(
    private val props: ItemDefinitionProperties,
) : ItemLookup {

    private val byId: Map<ItemId, Item> = props.catalog.entries.associate { (key, properties) ->
        val id = ItemId(key)
        id to properties.toItem(id)
    }

    override fun byId(id: ItemId): Item? = byId[id]

    override fun all(): List<Item> = byId.values.toList()

    private fun ItemProperties.toItem(id: ItemId): Item = Item(
        id = id,
        displayName = displayName,
        description = description,
        category = category,
        weightPerUnit = weightPerUnit,
        maxStack = maxStack,
        consumable = consumable?.let { ConsumableEffect(it.gauge, it.amount) },
    )
}
