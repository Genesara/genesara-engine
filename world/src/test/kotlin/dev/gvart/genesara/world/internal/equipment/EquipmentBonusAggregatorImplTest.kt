package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class EquipmentBonusAggregatorImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val other = AgentId(UUID.randomUUID())

    @Test
    fun `armorDef sums across equipped pieces matching the damage type`() {
        val chest = itemWithBonuses(
            "IRON_CHEST",
            slot = EquipSlot.CHEST,
            bonuses = listOf(
                EquippedBonus.ArmorDef(DamageType.SLASH, 8),
                EquippedBonus.ArmorDef(DamageType.PIERCE, 5),
            ),
        )
        val helmet = itemWithBonuses(
            "IRON_HELMET",
            slot = EquipSlot.HELMET,
            bonuses = listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 3)),
        )
        val aggregator = aggregatorWith(equipped = mapOf(EquipSlot.CHEST to chest, EquipSlot.HELMET to helmet))

        assertEquals(11, aggregator.armorDef(agent, DamageType.SLASH))
        assertEquals(5, aggregator.armorDef(agent, DamageType.PIERCE))
        assertEquals(0, aggregator.armorDef(agent, DamageType.BLUNT))
    }

    @Test
    fun `attributeBonus sums across equipped pieces matching the attribute`() {
        val chest = itemWithBonuses("CHEST", EquipSlot.CHEST, listOf(
            EquippedBonus.AttributeBonus(Attribute.CONSTITUTION, 5),
        ))
        val ring = itemWithBonuses("RING", EquipSlot.RING_LEFT, listOf(
            EquippedBonus.AttributeBonus(Attribute.CONSTITUTION, 2),
            EquippedBonus.AttributeBonus(Attribute.LUCK, 1),
        ))
        val aggregator = aggregatorWith(equipped = mapOf(EquipSlot.CHEST to chest, EquipSlot.RING_LEFT to ring))

        assertEquals(7, aggregator.attributeBonus(agent, Attribute.CONSTITUTION))
        assertEquals(1, aggregator.attributeBonus(agent, Attribute.LUCK))
        assertEquals(0, aggregator.attributeBonus(agent, Attribute.STRENGTH))
    }

    @Test
    fun `passiveBuff sums across equipped pieces matching the scaling effect`() {
        val boots = itemWithBonuses("BOOTS", EquipSlot.BOOTS, listOf(
            EquippedBonus.PassiveBuff(ScalingEffect.MOVEMENT_SPEED, 3),
        ))
        val gloves = itemWithBonuses("GLOVES", EquipSlot.GLOVES, listOf(
            EquippedBonus.PassiveBuff(ScalingEffect.STAMINA_REGEN, 1),
            EquippedBonus.PassiveBuff(ScalingEffect.MOVEMENT_SPEED, 1),
        ))
        val aggregator = aggregatorWith(equipped = mapOf(EquipSlot.BOOTS to boots, EquipSlot.GLOVES to gloves))

        assertEquals(4, aggregator.passiveBuff(agent, ScalingEffect.MOVEMENT_SPEED))
        assertEquals(1, aggregator.passiveBuff(agent, ScalingEffect.STAMINA_REGEN))
        assertEquals(0, aggregator.passiveBuff(agent, ScalingEffect.BLOCK_CHANCE))
    }

    @Test
    fun `returns zero for an agent with no equipped items`() {
        val aggregator = aggregatorWith(equipped = emptyMap())

        assertEquals(0, aggregator.armorDef(agent, DamageType.SLASH))
        assertEquals(0, aggregator.attributeBonus(agent, Attribute.CONSTITUTION))
        assertEquals(0, aggregator.passiveBuff(agent, ScalingEffect.MOVEMENT_SPEED))
    }

    @Test
    fun `bonuses scoped to the queried agent — other agents' gear is invisible`() {
        val chest = itemWithBonuses("CHEST", EquipSlot.CHEST, listOf(
            EquippedBonus.ArmorDef(DamageType.SLASH, 8),
        ))
        val store = InMemoryStore(mapOf(agent to mapOf(EquipSlot.CHEST to instanceOf(chest))))
        val items = StubItems(listOf(chest))
        val aggregator = EquipmentBonusAggregatorImpl(store, items)

        assertEquals(8, aggregator.armorDef(agent, DamageType.SLASH))
        assertEquals(0, aggregator.armorDef(other, DamageType.SLASH))
    }

    @Test
    fun `instance whose item is missing from the catalog is skipped, others still summed`() {
        val chest = itemWithBonuses("CHEST", EquipSlot.CHEST, listOf(
            EquippedBonus.ArmorDef(DamageType.SLASH, 5),
        ))
        val mysteryItemId = ItemId("PHANTOM")
        val mysteryItem = Item(
            id = mysteryItemId,
            displayName = "ghost",
            description = "",
            category = ItemCategory.EQUIPMENT,
            weightPerUnit = 0,
            maxStack = 1,
            validSlots = setOf(EquipSlot.HELMET),
        )
        val instances = mapOf(
            EquipSlot.CHEST to instanceOf(chest),
            EquipSlot.HELMET to instanceOf(mysteryItem),
        )
        val store = InMemoryStore(mapOf(agent to instances))
        // Mystery item NOT in items catalog → aggregator skips its bonuses.
        val items = StubItems(listOf(chest))
        val aggregator = EquipmentBonusAggregatorImpl(store, items)

        assertEquals(5, aggregator.armorDef(agent, DamageType.SLASH))
    }

    private fun itemWithBonuses(id: String, slot: EquipSlot, bonuses: List<EquippedBonus>): Item = Item(
        id = ItemId(id),
        displayName = id,
        description = "",
        category = ItemCategory.EQUIPMENT,
        weightPerUnit = 0,
        maxStack = 1,
        validSlots = setOf(slot),
        maxDurability = 100,
        bonuses = bonuses,
    )

    private fun aggregatorWith(equipped: Map<EquipSlot, Item>): EquipmentBonusAggregatorImpl {
        val instances = equipped.mapValues { (_, item) -> instanceOf(item) }
        val store = InMemoryStore(mapOf(agent to instances))
        val items = StubItems(equipped.values.toList())
        return EquipmentBonusAggregatorImpl(store, items)
    }

    private fun instanceOf(item: Item): EquipmentInstance = EquipmentInstance(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = item.id,
        rarity = Rarity.COMMON,
        durabilityCurrent = 100,
        durabilityMax = 100,
        creatorAgentId = agent,
        createdAtTick = 0L,
        equippedInSlot = item.validSlots.first(),
    )

    private class StubItems(items: List<Item>) : ItemLookup {
        private val byId = items.associateBy { it.id }
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class InMemoryStore(
        private val perAgent: Map<AgentId, Map<EquipSlot, EquipmentInstance>>,
    ) : EquipmentInstanceStore {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            perAgent[agentId].orEmpty()

        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? = error("not used")
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = error("not used")
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? = error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }
}
