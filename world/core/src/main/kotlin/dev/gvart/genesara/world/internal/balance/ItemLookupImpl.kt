package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.ConsumableEffect
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.ItemMaintenance
import org.springframework.stereotype.Component

@Component
class ItemLookupImpl(
    props: ItemDefinitionProperties,
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
        regenerating = regenerating,
        regenIntervalTicks = regenIntervalTicks,
        regenAmount = regenAmount,
        harvestSkill = harvestSkill?.let(::SkillId),
        rarity = rarity,
        maxDurability = maxDurability,
        validSlots = validSlots,
        twoHanded = twoHanded,
        requiredAttributes = requiredAttributes,
        // Convert string keys → typed SkillId once at catalog load. The YAML
        // side stays stringly-typed (`harvest-skill: ...`) while the public
        // `Item` carries SkillId, saving an allocation per equip-time iteration.
        requiredSkills = requiredSkills.mapKeys { (id, _) -> SkillId(id) },
        damageType = damageType,
        weaponPower = weaponPower,
        combatSkill = combatSkill?.let(::SkillId),
        range = range,
        bonuses = bonuses.map { it.bindToDomain(id.value) },
        extractionOnly = extractionOnly,
        mountSlots = mountSlots,
        maintenance = maintenance?.let { ItemMaintenance(it.type, it.value) },
        mountGearBonus = mountGearBonus,
    )
}
