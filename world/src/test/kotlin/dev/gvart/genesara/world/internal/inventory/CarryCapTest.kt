package dev.gvart.genesara.world.internal.inventory

import arrow.core.Either
import arrow.core.raise.either
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarryCapTest {

    private val agent = AgentId(UUID.randomUUID())
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val phantom = ItemId("PHANTOM")

    private val items = StubItemLookup(
        mapOf(
            wood to itemFor(wood, weight = 800),
            stone to itemFor(stone, weight = 1500),
        ),
    )

    @Test
    fun `totalGrams sums multi-stack inventory by catalog weight`() {
        val inventory = AgentInventory.EMPTY.add(wood, 3).add(stone, 2)
        assertEquals(5400, inventory.totalGrams(items))
    }

    @Test
    fun `totalGrams returns zero for empty inventory`() {
        assertEquals(0, AgentInventory.EMPTY.totalGrams(items))
    }

    @Test
    fun `totalGrams contributes zero for items missing from the catalog`() {
        val inventory = AgentInventory.EMPTY.add(wood, 1).add(phantom, 99)
        assertEquals(800, inventory.totalGrams(items))
    }

    @Test
    fun `equippedGrams sums each equipped instance via the catalog`() {
        val helmet = instance(itemId = wood)
        val chest = instance(itemId = stone)
        val equipped = mapOf(EquipSlot.HELMET to helmet, EquipSlot.CHEST to chest)
        assertEquals(2300, equippedGrams(equipped, items))
    }

    @Test
    fun `equippedGrams returns zero for empty map`() {
        assertEquals(0, equippedGrams(emptyMap(), items))
    }

    @Test
    fun `enforceCarryCap accepts when total lands exactly at the cap`() {
        val balance = balanceWithCarry(1000)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 5, currentGrams = 4000, additionalGrams = 1000, balance)
        }
        assertNull(result.leftOrNull())
    }

    @Test
    fun `enforceCarryCap rejects one gram over with OverEncumbered`() {
        val balance = balanceWithCarry(1000)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 5, currentGrams = 4000, additionalGrams = 1001, balance)
        }
        assertEquals(
            WorldRejection.OverEncumbered(agent, requested = 5001, capacity = 5000),
            result.leftOrNull(),
        )
    }

    @Test
    fun `enforceCarryCap rejects when strength is zero`() {
        val balance = balanceWithCarry(5000)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 0, currentGrams = 0, additionalGrams = 1, balance)
        }
        assertEquals(
            WorldRejection.OverEncumbered(agent, requested = 1, capacity = 0),
            result.leftOrNull(),
        )
    }

    @Test
    fun `enforceCarryCap accepts strength zero with a zero-gram add`() {
        val balance = balanceWithCarry(5000)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 0, currentGrams = 0, additionalGrams = 0, balance)
        }
        assertNull(result.leftOrNull())
    }

    @Test
    fun `enforceCarryCap rejects when Int multiplication would overflow current+add`() {
        val balance = balanceWithCarry(1000)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(
                agent,
                strength = 5,
                currentGrams = 1_500_000_000,
                additionalGrams = 1_500_000_000,
                balance,
            )
        }
        assertEquals(
            WorldRejection.OverEncumbered(agent, requested = Int.MAX_VALUE, capacity = 5000),
            result.leftOrNull(),
        )
    }

    @Test
    fun `enforceCarryCap clamps the cap to Int MAX_VALUE under permissive multipliers`() {
        val balance = balanceWithCarry(Int.MAX_VALUE)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 100, currentGrams = 0, additionalGrams = 1, balance)
        }
        assertNull(result.leftOrNull())
    }

    private fun itemFor(id: ItemId, weight: Int) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = weight,
        maxStack = 100,
    )

    private fun instance(itemId: ItemId) = EquipmentInstance(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = itemId,
        rarity = Rarity.COMMON,
        durabilityCurrent = 100,
        durabilityMax = 100,
        creatorAgentId = null,
        createdAtTick = 0L,
        equippedInSlot = EquipSlot.HELMET,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private fun balanceWithCarry(gramsPerStrengthPoint: Int) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun gatherStaminaCost(item: ItemId): Int = 1
        override fun gatherYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun carryGramsPerStrengthPoint(): Int = gramsPerStrengthPoint
    }
}
