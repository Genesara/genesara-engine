package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.ConsumableEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import org.springframework.stereotype.Component

@Component
internal class ItemLookupImpl(
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
        bonuses = bonuses.map { it.toDomain(id) },
    )

    private fun EquippedBonusProperties.toDomain(itemId: ItemId): EquippedBonus {
        val raw = target
        DamageType.entries.firstOrNull { it.name == raw }?.let { return EquippedBonus.ArmorDef(it, magnitude) }
        Attribute.entries.firstOrNull { it.name == raw }?.let { return EquippedBonus.AttributeBonus(it, magnitude) }
        ScalingEffect.entries.firstOrNull { it.name == raw }?.let { return EquippedBonus.PassiveBuff(it, magnitude) }
        error("${itemId.value}: unknown bonus target '$raw' (expected one of DamageType, Attribute, or ScalingEffect)")
    }
}
