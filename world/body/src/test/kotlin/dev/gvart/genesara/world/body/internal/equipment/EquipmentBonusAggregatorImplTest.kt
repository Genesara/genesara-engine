package dev.gvart.genesara.world.body.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
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
    fun `set bonus stacks on top of per-piece bonus when threshold is met`() {
        val helmet = itemWithBonuses("IRON_HELMET", EquipSlot.HELMET, listOf(
            EquippedBonus.ArmorDef(DamageType.SLASH, 2),
        ))
        val chest = itemWithBonuses("IRON_CHEST", EquipSlot.CHEST, listOf(
            EquippedBonus.ArmorDef(DamageType.SLASH, 3),
        ))
        val ironSet = dev.gvart.genesara.world.EquipmentSet(
            id = dev.gvart.genesara.world.EquipmentSetId("IRON"),
            pieces = setOf(helmet.id, chest.id),
            thresholds = mapOf(
                2 to dev.gvart.genesara.world.EquipmentSetThreshold(
                    bonuses = listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 4)),
                ),
            ),
        )
        val store = InMemoryStore(mapOf(agent to mapOf(
            EquipSlot.HELMET to instanceOf(helmet),
            EquipSlot.CHEST to instanceOf(chest),
        )))
        val items = StubItems(listOf(helmet, chest))
        val sets = SingleSetLookup(ironSet)
        val aggregator = EquipmentBonusAggregatorImpl(store, items, sets, NoOpBalance)

        // per-piece SLASH = 2 + 3 = 5; set 2-piece SLASH = 4 × 1.0 (COMMON avg) = 4; total = 9.
        assertEquals(9, aggregator.armorDef(agent, DamageType.SLASH))
    }

    @Test
    fun `set bonus tiers stack additively when count crosses multiple thresholds`() {
        val pieceIds = (1..4).map { ItemId("IRON_$it") }
        val pieces = pieceIds.map { id ->
            Item(
                id = id, displayName = id.value, description = "",
                category = ItemCategory.EQUIPMENT, weightPerUnit = 0, maxStack = 1,
                validSlots = setOf(EquipSlot.CHEST), maxDurability = 100,
            )
        }
        val ironSet = dev.gvart.genesara.world.EquipmentSet(
            id = dev.gvart.genesara.world.EquipmentSetId("IRON"),
            pieces = pieceIds.toSet(),
            thresholds = mapOf(
                2 to dev.gvart.genesara.world.EquipmentSetThreshold(
                    bonuses = listOf(EquippedBonus.AttributeBonus(Attribute.CONSTITUTION, 1)),
                ),
                4 to dev.gvart.genesara.world.EquipmentSetThreshold(
                    bonuses = listOf(EquippedBonus.AttributeBonus(Attribute.CONSTITUTION, 3)),
                ),
            ),
        )
        // Equipping all 4 pieces — but only one slot can hold each; for the aggregator test
        // we just need any 4 instances in any slots since the aggregator counts membership, not slot.
        val instances = listOf(EquipSlot.HELMET, EquipSlot.CHEST, EquipSlot.PANTS, EquipSlot.BOOTS)
            .zip(pieces).associate { (slot, item) -> slot to instanceOf(item) }
        val store = InMemoryStore(mapOf(agent to instances))
        val items = StubItems(pieces)
        val aggregator = EquipmentBonusAggregatorImpl(store, items, SingleSetLookup(ironSet), NoOpBalance)

        // 4 pieces equipped → 2-piece tier (+1) + 4-piece tier (+3) = +4 CON.
        assertEquals(4, aggregator.attributeBonus(agent, Attribute.CONSTITUTION))
    }

    @Test
    fun `set bonus uses average-rarity multiplier from BalanceLookup`() {
        val helmet = itemWithBonuses("IRON_HELMET", EquipSlot.HELMET, emptyList())
        val chest = itemWithBonuses("IRON_CHEST", EquipSlot.CHEST, emptyList())
        val ironSet = dev.gvart.genesara.world.EquipmentSet(
            id = dev.gvart.genesara.world.EquipmentSetId("IRON"),
            pieces = setOf(helmet.id, chest.id),
            thresholds = mapOf(
                2 to dev.gvart.genesara.world.EquipmentSetThreshold(
                    bonuses = listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 10)),
                ),
            ),
        )
        // Two pieces with rarities RARE (ordinal 2) and EPIC (ordinal 3) — mean 2.5 → rounds
        // to 3 (EPIC). Multiplier per default curve: 1.75. Expected armorDef = 10 × 1.75 = 17.5 → 18.
        val rareHelmet = instanceOf(helmet).copy(rarity = Rarity.RARE)
        val epicChest = instanceOf(chest).copy(rarity = Rarity.EPIC)
        val store = InMemoryStore(mapOf(agent to mapOf(
            EquipSlot.HELMET to rareHelmet,
            EquipSlot.CHEST to epicChest,
        )))
        val items = StubItems(listOf(helmet, chest))
        val aggregator = EquipmentBonusAggregatorImpl(store, items, SingleSetLookup(ironSet), NoOpBalance)

        assertEquals(18, aggregator.armorDef(agent, DamageType.SLASH))
    }

    @Test
    fun `equipping only one piece below the lowest threshold yields no set bonus`() {
        val helmet = itemWithBonuses("IRON_HELMET", EquipSlot.HELMET, emptyList())
        val chest = itemWithBonuses("IRON_CHEST", EquipSlot.CHEST, emptyList())
        val ironSet = dev.gvart.genesara.world.EquipmentSet(
            id = dev.gvart.genesara.world.EquipmentSetId("IRON"),
            pieces = setOf(helmet.id, chest.id),
            thresholds = mapOf(2 to dev.gvart.genesara.world.EquipmentSetThreshold(
                bonuses = listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 5)),
            )),
        )
        val store = InMemoryStore(mapOf(agent to mapOf(EquipSlot.HELMET to instanceOf(helmet))))
        val items = StubItems(listOf(helmet, chest))
        val aggregator = EquipmentBonusAggregatorImpl(store, items, SingleSetLookup(ironSet), NoOpBalance)

        assertEquals(0, aggregator.armorDef(agent, DamageType.SLASH))
    }

    @Test
    fun `bonuses scoped to the queried agent — other agents' gear is invisible`() {
        val chest = itemWithBonuses("CHEST", EquipSlot.CHEST, listOf(
            EquippedBonus.ArmorDef(DamageType.SLASH, 8),
        ))
        val store = InMemoryStore(mapOf(agent to mapOf(EquipSlot.CHEST to instanceOf(chest))))
        val items = StubItems(listOf(chest))
        val aggregator = EquipmentBonusAggregatorImpl(store, items, EmptySetLookup, NoOpBalance)

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
        val aggregator = EquipmentBonusAggregatorImpl(store, items, EmptySetLookup, NoOpBalance)

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
        return EquipmentBonusAggregatorImpl(store, items, EmptySetLookup, NoOpBalance)
    }

    private fun instanceOf(item: Item): ItemInstance.Equipment = ItemInstance.Equipment(
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

    private object EmptySetLookup : dev.gvart.genesara.world.EquipmentSetLookup {
        override fun byId(id: dev.gvart.genesara.world.EquipmentSetId): dev.gvart.genesara.world.EquipmentSet? = null
        override fun all(): List<dev.gvart.genesara.world.EquipmentSet> = emptyList()
        override fun setsContaining(itemId: ItemId): List<dev.gvart.genesara.world.EquipmentSet> = emptyList()
    }

    private class SingleSetLookup(private val set: dev.gvart.genesara.world.EquipmentSet) : dev.gvart.genesara.world.EquipmentSetLookup {
        override fun byId(id: dev.gvart.genesara.world.EquipmentSetId): dev.gvart.genesara.world.EquipmentSet? =
            set.takeIf { it.id == id }

        override fun all(): List<dev.gvart.genesara.world.EquipmentSet> = listOf(set)

        override fun setsContaining(itemId: ItemId): List<dev.gvart.genesara.world.EquipmentSet> =
            if (itemId in set.pieces) listOf(set) else emptyList()
    }

    private object NoOpBalance : dev.gvart.genesara.world.internal.balance.BalanceLookup {
        override fun moveStaminaCost(biome: dev.gvart.genesara.world.Biome, climate: dev.gvart.genesara.world.Climate, terrain: dev.gvart.genesara.world.Terrain): Int = 1
        override fun staminaRegenPerTick(climate: dev.gvart.genesara.world.Climate): Int = 0
        override fun resourceSpawnsFor(terrain: dev.gvart.genesara.world.Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 1
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: dev.gvart.genesara.world.Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: dev.gvart.genesara.world.Terrain): Boolean = true
    }

    private class InMemoryStore(
        private val perAgent: Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>>,
    ) : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
            perAgent[agentId].orEmpty()

        override fun equippedForAll(agents: Set<AgentId>): Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>> =
            perAgent.filterKeys { it in agents }

        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }
}
