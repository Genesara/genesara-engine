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
        // 3 * 800 + 2 * 1500 = 2400 + 3000
        assertEquals(5400, inventory.totalGrams(items))
    }

    @Test
    fun `totalGrams returns zero for empty inventory`() {
        assertEquals(0, AgentInventory.EMPTY.totalGrams(items))
    }

    @Test
    fun `totalGrams contributes zero for items missing from the catalog`() {
        // Stale stack pointing at a removed catalog entry — tolerate, don't crash.
        val inventory = AgentInventory.EMPTY.add(wood, 1).add(phantom, 99)
        assertEquals(800, inventory.totalGrams(items))
    }

    @Test
    fun `equippedGrams sums each equipped instance via the catalog`() {
        val helmet = instance(itemId = wood)   // 800 g
        val chest = instance(itemId = stone)   // 1500 g
        val equipped = mapOf(EquipSlot.HELMET to helmet, EquipSlot.CHEST to chest)
        assertEquals(2300, equippedGrams(equipped, items))
    }

    @Test
    fun `equippedGrams returns zero for empty map`() {
        assertEquals(0, equippedGrams(emptyMap(), items))
    }

    @Test
    fun `enforceCarryCap accepts when total lands exactly at the cap`() {
        // Strength 5 * 1000 g/pt = 5000 g cap. Existing 4000 + add 1000 = 5000.
        val balance = balanceWithCarry(1000)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 5, currentGrams = 4000, additionalGrams = 1000, balance)
        }
        assertNull(result.leftOrNull(), "expected helper to accept at the cap")
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
        // Strength 0 * any = 0 cap; any add is over.
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
        // Cap = 0; requested = 0 → equal, not over. Boundary case for callers that
        // might invoke the gate with no payload (e.g. a future no-op pickup).
        val balance = balanceWithCarry(5000)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 0, currentGrams = 0, additionalGrams = 0, balance)
        }
        assertNull(result.leftOrNull())
    }

    @Test
    fun `enforceCarryCap rejects when Int multiplication would overflow current+add`() {
        // Heavy item × big yield: current = 1.5 GB, add = 1.5 GB. Naive Int math
        // wraps; Long widening should catch this and reject with a clamped Int payload.
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
        // capacity = 5 × 1000 = 5000; requested clamps to Int.MAX_VALUE.
        assertEquals(
            WorldRejection.OverEncumbered(agent, requested = Int.MAX_VALUE, capacity = 5000),
            result.leftOrNull(),
        )
    }

    @Test
    fun `enforceCarryCap clamps the cap to Int MAX_VALUE under permissive multipliers`() {
        // strength 100 × Int.MAX_VALUE overflows in Long → clamped to Int.MAX_VALUE
        // for the rejection's capacity field. With current 0 and add 1 we still fit
        // way below — assert this passes (no rejection).
        val balance = balanceWithCarry(Int.MAX_VALUE)
        val result: Either<WorldRejection, Unit> = either {
            enforceCarryCap(agent, strength = 100, currentGrams = 0, additionalGrams = 1, balance)
        }
        assertNull(result.leftOrNull())
    }

    // ──────────────────────────── Test fixtures ────────────────────────────

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
