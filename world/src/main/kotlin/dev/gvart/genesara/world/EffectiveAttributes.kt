package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Attribute

/**
 * Composes an agent's [AgentAttributes] from base + equipped attribute bonuses.
 *
 * Used by derived-pool calculations (max HP from CON, etc.) so equipped gear
 * with `AttributeBonus` entries actually affects body maxes. Never used by
 * `required-attributes` equip checks — those always read base (per ADR-0001,
 * to prevent recursive bootstrap where +CON gear qualifies the agent for
 * higher-CON gear).
 *
 * TODO(equipment-effective): extend usage to carry capacity (STR), sight range
 *   (PER), and any future attribute-driven derived stat. Slice 4 only wires
 *   max pools; the rest still read base attributes.
 */
object EffectiveAttributes {

    fun compute(base: AgentAttributes, agent: AgentId, bonuses: EquipmentBonusAggregator): AgentAttributes =
        AgentAttributes(
            strength = base.strength + bonuses.attributeBonus(agent, Attribute.STRENGTH),
            dexterity = base.dexterity + bonuses.attributeBonus(agent, Attribute.DEXTERITY),
            constitution = base.constitution + bonuses.attributeBonus(agent, Attribute.CONSTITUTION),
            perception = base.perception + bonuses.attributeBonus(agent, Attribute.PERCEPTION),
            intelligence = base.intelligence + bonuses.attributeBonus(agent, Attribute.INTELLIGENCE),
            luck = base.luck + bonuses.attributeBonus(agent, Attribute.LUCK),
        )
}
