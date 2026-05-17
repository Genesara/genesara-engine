package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.LootEntry
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.internal.balance.RarityRoller
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LootRollTest {

    private val wolf = NpcType("GRAY_WOLF")
    private val node = NodeId(7L)

    @Test
    fun `entry below drop chance is skipped`() {
        val items = singleStackable(ItemId("HIDE"))
        val tables = catalog(wolf, LootEntry(ItemId("HIDE"), dropChance = 0.0, quantityMin = 1, quantityMax = 1))
        val sink = CapturingGroundItemStore()
        val roll = LootRoll(tables, items, sink, RarityRoller(Random(0L)))

        val drops = roll.rollAndDeposit(wolf, node, 0, 0, huntingLootBonus = 0.0, tick = 1L, rng = Random(0L))

        assertEquals(emptyList(), drops)
        assertEquals(0, sink.deposited.size)
    }

    @Test
    fun `stackable drop yields uniform quantity in range`() {
        val items = singleStackable(ItemId("HIDE"))
        val tables = catalog(wolf, LootEntry(ItemId("HIDE"), dropChance = 1.0, quantityMin = 2, quantityMax = 5))
        val sink = CapturingGroundItemStore()
        val roll = LootRoll(tables, items, sink, RarityRoller(Random(0L)))

        repeat(50) {
            val drops = roll.rollAndDeposit(wolf, node, 0, 0, huntingLootBonus = 0.0, tick = 1L, rng = Random(it.toLong()))
            assertEquals(1, drops.size)
            val dropped = drops.first()
            assertTrue(dropped is DroppedItemView.Stackable)
            assertTrue((dropped as DroppedItemView.Stackable).quantity in 2..5)
        }
    }

    @Test
    fun `equipment drop rolls rarity via RarityRoller`() {
        val sword = Item(
            id = ItemId("IRON_SWORD"),
            displayName = "Iron Sword",
            description = "",
            category = ItemCategory.EQUIPMENT,
            weightPerUnit = 100,
            maxStack = 1,
            maxDurability = 50,
        )
        val items = MapItemLookup(mapOf(sword.id to sword))
        val tables = catalog(wolf, LootEntry(sword.id, dropChance = 1.0, quantityMin = 1, quantityMax = 1))
        val sink = CapturingGroundItemStore()
        val roll = LootRoll(tables, items, sink, RarityRoller(Random(99L)))

        val drops = roll.rollAndDeposit(wolf, node, 50, 30, huntingLootBonus = 0.0, tick = 5L, rng = Random(7L))

        assertEquals(1, drops.size)
        val drop = drops.first()
        assertTrue(drop is DroppedItemView.Equipment)
        assertNotNull((drop as DroppedItemView.Equipment).rarity)
    }

    @Test
    fun `independent rolls allow multiple entries to drop in one kill`() {
        val items = MapItemLookup(
            mapOf(
                ItemId("HIDE") to stackableItem(ItemId("HIDE")),
                ItemId("BERRY") to stackableItem(ItemId("BERRY")),
            ),
        )
        val tables = catalog(
            wolf,
            LootEntry(ItemId("HIDE"), dropChance = 1.0, quantityMin = 1, quantityMax = 1),
            LootEntry(ItemId("BERRY"), dropChance = 1.0, quantityMin = 1, quantityMax = 1),
        )
        val sink = CapturingGroundItemStore()
        val roll = LootRoll(tables, items, sink, RarityRoller(Random(0L)))

        val drops = roll.rollAndDeposit(wolf, node, 0, 0, huntingLootBonus = 0.0, tick = 1L, rng = Random(1L))

        assertEquals(2, drops.size)
        assertTrue(drops.any { (it as DroppedItemView.Stackable).item == ItemId("HIDE") })
        assertTrue(drops.any { (it as DroppedItemView.Stackable).item == ItemId("BERRY") })
    }

    @Test
    fun `hunting loot bonus shifts the stackable quantity toward max without exceeding it`() {
        val items = singleStackable(ItemId("HIDE"))
        val tables = catalog(wolf, LootEntry(ItemId("HIDE"), dropChance = 1.0, quantityMin = 1, quantityMax = 5))
        val sink = CapturingGroundItemStore()
        val roll = LootRoll(tables, items, sink, RarityRoller(Random(0L)))

        // Same RNG seed; with a hunting bonus shifting +floor(0.5 × 4) = +2 the quantity
        // is strictly greater than the base roll (and stays clamped at 5).
        val baseDrops = roll.rollAndDeposit(
            wolf, node, 0, 0, huntingLootBonus = 0.0, tick = 1L, rng = Random(42L),
        )
        val boostedDrops = roll.rollAndDeposit(
            wolf, node, 0, 0, huntingLootBonus = 0.5, tick = 1L, rng = Random(42L),
        )

        val base = (baseDrops.single() as DroppedItemView.Stackable).quantity
        val boosted = (boostedDrops.single() as DroppedItemView.Stackable).quantity
        assertTrue(boosted >= base, "hunting bonus should not reduce quantity (was $base → $boosted)")
        assertTrue(boosted <= 5, "boosted quantity must never exceed quantityMax (got $boosted)")
        assertEquals(base + 2, boosted, "bonus 0.5 × range 4 = +2 shift on the base roll")
    }

    @Test
    fun `hunting loot bonus saturated at one yields max quantity`() {
        val items = singleStackable(ItemId("HIDE"))
        val tables = catalog(wolf, LootEntry(ItemId("HIDE"), dropChance = 1.0, quantityMin = 1, quantityMax = 4))
        val sink = CapturingGroundItemStore()
        val roll = LootRoll(tables, items, sink, RarityRoller(Random(0L)))

        repeat(20) {
            val drops = roll.rollAndDeposit(
                wolf, node, 0, 0, huntingLootBonus = 1.0, tick = 1L, rng = Random(it.toLong()),
            )
            assertEquals(4, (drops.single() as DroppedItemView.Stackable).quantity)
        }
    }

    private fun stackableItem(id: ItemId) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 10,
        maxStack = 200,
    )

    private fun singleStackable(id: ItemId): ItemLookup =
        MapItemLookup(mapOf(id to stackableItem(id)))

    private class MapItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private fun catalog(mob: NpcType, vararg entries: LootEntry): LootTableCatalog =
        object : LootTableCatalog {
            override fun byMob(mob2: NpcType): List<LootEntry> =
                if (mob2 == mob) entries.toList() else emptyList()
            override fun allMobs(): Set<NpcType> = setOf(mob)
        }

    private class CapturingGroundItemStore : GroundItemStore {
        val deposited = mutableListOf<Pair<NodeId, DroppedItemView>>()
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) {
            deposited += node to drop
        }
        override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
    }
}
