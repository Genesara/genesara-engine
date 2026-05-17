package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

@Component
class EquipmentBonusAggregatorImpl(
    private val equipment: AgentItemInstancesStore,
    private val items: ItemLookup,
    private val sets: EquipmentSetLookup,
    private val balance: BalanceLookup,
) : EquipmentBonusAggregator {

    override fun armorDef(agent: AgentId, damageType: DamageType): Int =
        total(agent) { it is EquippedBonus.ArmorDef && it.damageType == damageType }

    override fun attributeBonus(agent: AgentId, attribute: Attribute): Int =
        total(agent) { it is EquippedBonus.AttributeBonus && it.attribute == attribute }

    override fun passiveBuff(agent: AgentId, effect: ScalingEffect): Int =
        total(agent) { it is EquippedBonus.PassiveBuff && it.effect == effect }

    override fun passiveBuffBatch(agents: Set<AgentId>, effect: ScalingEffect): Map<AgentId, Int> {
        if (agents.isEmpty()) return emptyMap()
        val equippedByAgent = equipment.equippedForAll(agents)
        if (equippedByAgent.isEmpty()) return emptyMap()
        val match: (EquippedBonus) -> Boolean = { it is EquippedBonus.PassiveBuff && it.effect == effect }
        val result = mutableMapOf<AgentId, Int>()
        for ((agent, equipped) in equippedByAgent) {
            val value = perPieceSum(equipped, match) + setBonusSum(equipped, match)
            if (value != 0) result[agent] = value
        }
        return result
    }

    private inline fun total(agent: AgentId, match: (EquippedBonus) -> Boolean): Int {
        val equipped = equipment.equippedFor(agent)
        if (equipped.isEmpty()) return 0
        return perPieceSum(equipped, match) + setBonusSum(equipped, match)
    }

    private inline fun perPieceSum(
        equipped: Map<EquipSlot, ItemInstance.Equipment>,
        match: (EquippedBonus) -> Boolean,
    ): Int {
        var acc = 0
        for ((_, instance) in equipped) {
            val item = items.byId(instance.itemId) ?: continue
            for (bonus in item.bonuses) if (match(bonus)) acc += bonus.magnitude
        }
        return acc
    }

    private inline fun setBonusSum(
        equipped: Map<EquipSlot, ItemInstance.Equipment>,
        match: (EquippedBonus) -> Boolean,
    ): Int {
        // Walk instances once, grouping by set membership. An item can belong
        // to multiple sets (ADR-0002), so a single instance may push multiple
        // counts and rarity contributions.
        val contributions = mutableMapOf<EquipmentSet, MutableList<Rarity>>()
        for ((_, instance) in equipped) {
            for (set in sets.setsContaining(instance.itemId)) {
                contributions.getOrPut(set) { mutableListOf() }.add(instance.rarity)
            }
        }
        if (contributions.isEmpty()) return 0
        var acc = 0
        for ((set, rarities) in contributions) {
            val activeBonuses = set.activeBonuses(rarities.size)
            if (activeBonuses.isEmpty()) continue
            val avgRarity = averageRarity(rarities)
            val multiplier = balance.rarityMultiplier(avgRarity)
            for (bonus in activeBonuses) {
                if (match(bonus)) {
                    acc += (bonus.magnitude * multiplier).roundToInt()
                }
            }
        }
        return acc
    }

    /**
     * Average ordinal across [rarities], rounded **half-up** (Kotlin
     * `Double.roundToInt` is half-away-from-zero; all ordinals are
     * non-negative so it collapses to half-up here). Mean 2.5 rounds to
     * 3 (EPIC), not 2 (RARE). Mixed loadouts smooth through the rarity
     * multiplier curve in [BalanceLookup] — see ADR-0002.
     */
    private fun averageRarity(rarities: List<Rarity>): Rarity {
        val mean = rarities.sumOf { it.ordinal }.toDouble() / rarities.size
        val rounded = mean.roundToInt().coerceIn(0, Rarity.entries.lastIndex)
        return Rarity.entries[rounded]
    }
}
