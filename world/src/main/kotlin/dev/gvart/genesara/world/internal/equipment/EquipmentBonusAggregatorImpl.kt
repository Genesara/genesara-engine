package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.ItemLookup
import org.springframework.stereotype.Component

@Component
internal class EquipmentBonusAggregatorImpl(
    private val equipment: EquipmentInstanceStore,
    private val items: ItemLookup,
) : EquipmentBonusAggregator {

    override fun armorDef(agent: AgentId, damageType: DamageType): Int =
        sum(agent) { it is EquippedBonus.ArmorDef && it.damageType == damageType }

    override fun attributeBonus(agent: AgentId, attribute: Attribute): Int =
        sum(agent) { it is EquippedBonus.AttributeBonus && it.attribute == attribute }

    override fun passiveBuff(agent: AgentId, effect: ScalingEffect): Int =
        sum(agent) { it is EquippedBonus.PassiveBuff && it.effect == effect }

    private inline fun sum(agent: AgentId, match: (EquippedBonus) -> Boolean): Int {
        val equipped = equipment.equippedFor(agent)
        if (equipped.isEmpty()) return 0
        var acc = 0
        for ((_, instance) in equipped) {
            val item = items.byId(instance.itemId) ?: continue
            for (bonus in item.bonuses) {
                if (match(bonus)) acc += bonus.magnitude
            }
        }
        return acc
    }
}
