package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect

/**
 * Sums [EquippedBonus] magnitudes across an agent's currently-equipped
 * instances. Returns 0 when the agent has no equipped items, no items in
 * the requested bucket, or no entries matching the requested key.
 *
 * Reads from [EquipmentInstanceStore] + [ItemLookup] at call time — no
 * caching layer. Combat / equip / derived-pool reducers are the hot
 * consumers; each call walks the agent's ≤12 equipped instances once.
 *
 * Slice 1: only [armorDef] is consumed (by the combat reducer). The other
 * methods exist so the data model is fully addressable from downstream
 * slices without churn here. See ADR-0001.
 */
interface EquipmentBonusAggregator {

    fun armorDef(agent: AgentId, damageType: DamageType): Int

    fun attributeBonus(agent: AgentId, attribute: Attribute): Int

    fun passiveBuff(agent: AgentId, effect: ScalingEffect): Int

    /**
     * Batched form of [passiveBuff] — one query for [agents], one [effect].
     * Used by per-tick consumers (passive sweep) where issuing N
     * single-agent queries would cost N DB round-trips. Agents with no
     * matching bonus are absent from the map.
     *
     * Default impl falls back to N single-agent calls for stub
     * implementations; production
     * [dev.gvart.genesara.world.internal.equipment.EquipmentBonusAggregatorImpl]
     * overrides with a single batched [EquipmentInstanceStore.equippedForAll]
     * call.
     */
    fun passiveBuffBatch(agents: Set<AgentId>, effect: ScalingEffect): Map<AgentId, Int> =
        agents.associateWith { passiveBuff(it, effect) }.filterValues { it != 0 }

    companion object {
        /** Empty-result aggregator for tests that don't exercise equipment bonuses. */
        val NoBonuses: EquipmentBonusAggregator = object : EquipmentBonusAggregator {
            override fun armorDef(agent: AgentId, damageType: DamageType): Int = 0
            override fun attributeBonus(agent: AgentId, attribute: Attribute): Int = 0
            override fun passiveBuff(agent: AgentId, effect: ScalingEffect): Int = 0
        }
    }
}
