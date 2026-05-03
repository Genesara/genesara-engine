package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ChestReducersTest {

    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val otherNodeId = NodeId(2L)
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")

    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.PLAINS, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private val items = StubItemLookup(
        mapOf(
            wood to itemFor(wood, weightPerUnit = 800),
            stone to itemFor(stone, weightPerUnit = 1500),
        ),
    )
    private val catalog = BuildingsCatalog(
        BuildingDefinitionProperties(
            catalog = mapOf(
                "STORAGE_CHEST" to BuildingProperties(
                    requiredSkill = "CARPENTRY",
                    totalSteps = 8,
                    staminaPerStep = 8,
                    hp = 40,
                    categoryHint = BuildingCategoryHint.STORAGE,
                    totalMaterials = mapOf("WOOD" to 20),
                    chestCapacityGrams = 50_000,
                ),
            ),
        ),
    )

    private fun stateAt(
        node: NodeId = nodeId,
        inventory: Map<ItemId, Int> = mapOf(wood to 50),
        positioned: Boolean = true,
    ): WorldState {
        var inv = AgentInventory()
        for ((item, qty) in inventory) inv = inv.add(item, qty)
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(
                nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
                otherNodeId to Node(otherNodeId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
            ),
            positions = if (positioned) mapOf(agent to node) else emptyMap(),
            bodies = mapOf(agent to AgentBody(50, 50, 50, 50, 0, 0)),
            inventories = mapOf(agent to inv),
        )
    }

    private fun chest(
        owner: AgentId = agent,
        node: NodeId = nodeId,
        status: BuildingStatus = BuildingStatus.ACTIVE,
        type: BuildingType = BuildingType.STORAGE_CHEST,
    ): Building = Building(
        instanceId = UUID.randomUUID(),
        nodeId = node,
        type = type,
        status = status,
        builtByAgentId = owner,
        builtAtTick = 1L,
        lastProgressTick = 1L,
        progressSteps = if (status == BuildingStatus.ACTIVE) 8 else 1,
        totalSteps = 8,
        hpCurrent = 40,
        hpMax = 40,
    )

    // ─────────────────────── Deposit ───────────────────────

    @Test
    fun `deposit moves items from inventory to chest and emits ItemDeposited`() {
        val c = chest()
        val store = StubBuildings(c)
        val contents = StubChestContents()

        val (next, event) = assertNotNull(
            reduceDeposit(
                stateAt(), WorldCommand.DepositToChest(agent, c.instanceId, wood, 5),
                items, catalog, store, contents, tick = 7,
            ).getOrNull(),
        )

        assertEquals(45, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(5, contents.quantityOf(c.instanceId, wood))
        val deposited = assertIs<WorldEvent.ItemDeposited>(event)
        assertEquals(c.instanceId, deposited.chest)
        assertEquals(wood, deposited.item)
        assertEquals(5, deposited.quantity)
        assertEquals(7L, deposited.tick)
    }

    @Test
    fun `deposit rejects when chest does not exist`() {
        val store = StubBuildings()
        val phantom = UUID.randomUUID()

        val result = reduceDeposit(
            stateAt(), WorldCommand.DepositToChest(agent, phantom, wood, 1),
            items, catalog, store, StubChestContents(), tick = 1,
        )

        assertEquals(WorldRejection.BuildingNotFound(phantom), result.leftOrNull())
    }

    @Test
    fun `deposit rejects when target building is not a chest`() {
        val notChest = chest(type = BuildingType.WORKBENCH)
        val store = StubBuildings(notChest)

        val result = reduceDeposit(
            stateAt(), WorldCommand.DepositToChest(agent, notChest.instanceId, wood, 1),
            items, catalog, store, StubChestContents(), tick = 1,
        )

        // Surfaces as BuildingNotFound to avoid leaking what other building types are at this id.
        assertEquals(WorldRejection.BuildingNotFound(notChest.instanceId), result.leftOrNull())
    }

    @Test
    fun `deposit rejects when chest is owned by another agent`() {
        val theirs = chest(owner = otherAgent)
        val store = StubBuildings(theirs)

        val result = reduceDeposit(
            stateAt(), WorldCommand.DepositToChest(agent, theirs.instanceId, wood, 1),
            items, catalog, store, StubChestContents(), tick = 1,
        )

        assertEquals(WorldRejection.NotChestOwner(agent, theirs.instanceId), result.leftOrNull())
    }

    @Test
    fun `deposit rejects when agent is not on the chest's node`() {
        val c = chest()
        val store = StubBuildings(c)

        val result = reduceDeposit(
            stateAt(node = otherNodeId), WorldCommand.DepositToChest(agent, c.instanceId, wood, 1),
            items, catalog, store, StubChestContents(), tick = 1,
        )

        assertEquals(WorldRejection.NotOnBuildingNode(agent, c.instanceId), result.leftOrNull())
    }

    @Test
    fun `deposit rejects against an UNDER_CONSTRUCTION chest`() {
        val halfBuilt = chest(status = BuildingStatus.UNDER_CONSTRUCTION)
        val store = StubBuildings(halfBuilt)

        val result = reduceDeposit(
            stateAt(), WorldCommand.DepositToChest(agent, halfBuilt.instanceId, wood, 1),
            items, catalog, store, StubChestContents(), tick = 1,
        )

        assertEquals(
            WorldRejection.BuildingNotActive(halfBuilt.instanceId, BuildingStatus.UNDER_CONSTRUCTION),
            result.leftOrNull(),
        )
    }

    @Test
    fun `deposit rejects when agent does not have enough of the item`() {
        val c = chest()
        val store = StubBuildings(c)

        val result = reduceDeposit(
            stateAt(inventory = mapOf(wood to 2)),
            WorldCommand.DepositToChest(agent, c.instanceId, wood, 5),
            items, catalog, store, StubChestContents(), tick = 1,
        )

        assertEquals(WorldRejection.ItemNotInInventory(agent, wood), result.leftOrNull())
    }

    @Test
    fun `deposit rejects when the chest's weight cap would be exceeded`() {
        // Capacity 50_000g; wood is 800g/unit. Pre-load 60 units (48_000g) so a deposit of
        // 5 more (4_000g) would push to 52_000 — over cap.
        val c = chest()
        val store = StubBuildings(c)
        val contents = StubChestContents().also { it.add(c.instanceId, wood, 60) }

        val result = reduceDeposit(
            stateAt(inventory = mapOf(wood to 50)),
            WorldCommand.DepositToChest(agent, c.instanceId, wood, 5),
            items, catalog, store, contents, tick = 1,
        )

        val rejection = assertIs<WorldRejection.ChestCapacityExceeded>(result.leftOrNull())
        assertEquals(c.instanceId, rejection.chest)
        assertEquals(52_000, rejection.attemptedGrams)
        assertEquals(50_000, rejection.capacityGrams)
        assertEquals(60, contents.quantityOf(c.instanceId, wood), "no add on rejection")
    }

    @Test
    fun `deposit accepts when totals land exactly at the chest's weight cap`() {
        // 50_000 / 800 = 62.5 → 62 units = 49_600g ≤ cap.
        val c = chest()
        val store = StubBuildings(c)
        val contents = StubChestContents()

        val (_, event) = assertNotNull(
            reduceDeposit(
                stateAt(inventory = mapOf(wood to 100)),
                WorldCommand.DepositToChest(agent, c.instanceId, wood, 62),
                items, catalog, store, contents, tick = 1,
            ).getOrNull(),
        )

        assertIs<WorldEvent.ItemDeposited>(event)
        assertEquals(62, contents.quantityOf(c.instanceId, wood))
    }

    @Test
    fun `deposit rejects non-positive quantity as a WorldRejection — never an exception`() {
        val c = chest()
        val store = StubBuildings(c)

        val zero = reduceDeposit(
            stateAt(), WorldCommand.DepositToChest(agent, c.instanceId, wood, 0),
            items, catalog, store, StubChestContents(), tick = 1,
        )
        assertEquals(WorldRejection.NonPositiveQuantity(agent, 0), zero.leftOrNull())

        val negative = reduceDeposit(
            stateAt(), WorldCommand.DepositToChest(agent, c.instanceId, wood, -3),
            items, catalog, store, StubChestContents(), tick = 1,
        )
        assertEquals(WorldRejection.NonPositiveQuantity(agent, -3), negative.leftOrNull())
    }

    @Test
    fun `withdraw rejects non-positive quantity as a WorldRejection`() {
        val c = chest()
        val store = StubBuildings(c)

        val result = reduceWithdraw(
            stateAt(), WorldCommand.WithdrawFromChest(agent, c.instanceId, wood, 0),
            store, StubChestContents(), tick = 1,
        )
        assertEquals(WorldRejection.NonPositiveQuantity(agent, 0), result.leftOrNull())
    }

    // ─────────────────────── Withdraw ───────────────────────

    @Test
    fun `withdraw moves items from chest into inventory and emits ItemWithdrawn`() {
        val c = chest()
        val store = StubBuildings(c)
        val contents = StubChestContents().also { it.add(c.instanceId, wood, 10) }

        val (next, event) = assertNotNull(
            reduceWithdraw(
                stateAt(inventory = emptyMap()),
                WorldCommand.WithdrawFromChest(agent, c.instanceId, wood, 4),
                store, contents, tick = 7,
            ).getOrNull(),
        )

        assertEquals(4, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(6, contents.quantityOf(c.instanceId, wood))
        val withdrawn = assertIs<WorldEvent.ItemWithdrawn>(event)
        assertEquals(c.instanceId, withdrawn.chest)
        assertEquals(wood, withdrawn.item)
        assertEquals(4, withdrawn.quantity)
        assertEquals(7L, withdrawn.tick)
    }

    @Test
    fun `withdraw rejects when chest does not contain enough of the item`() {
        val c = chest()
        val store = StubBuildings(c)
        val contents = StubChestContents().also { it.add(c.instanceId, wood, 2) }

        val result = reduceWithdraw(
            stateAt(), WorldCommand.WithdrawFromChest(agent, c.instanceId, wood, 5),
            store, contents, tick = 1,
        )

        val rejection = assertIs<WorldRejection.ChestDoesNotContain>(result.leftOrNull())
        assertEquals(c.instanceId, rejection.chest)
        assertEquals(5, rejection.requested)
        assertEquals(2, rejection.available)
        assertEquals(2, contents.quantityOf(c.instanceId, wood), "no decrement on rejection")
    }

    @Test
    fun `withdraw rejects when chest holds zero of the item`() {
        val c = chest()
        val store = StubBuildings(c)

        val result = reduceWithdraw(
            stateAt(), WorldCommand.WithdrawFromChest(agent, c.instanceId, wood, 1),
            store, StubChestContents(), tick = 1,
        )

        assertEquals(
            WorldRejection.ChestDoesNotContain(c.instanceId, wood, 1, 0),
            result.leftOrNull(),
        )
    }

    @Test
    fun `withdraw rejects when agent is not the chest owner`() {
        val theirs = chest(owner = otherAgent)
        val store = StubBuildings(theirs)
        val contents = StubChestContents().also { it.add(theirs.instanceId, wood, 10) }

        val result = reduceWithdraw(
            stateAt(), WorldCommand.WithdrawFromChest(agent, theirs.instanceId, wood, 1),
            store, contents, tick = 1,
        )

        assertEquals(WorldRejection.NotChestOwner(agent, theirs.instanceId), result.leftOrNull())
    }

    @Test
    fun `withdraw rejects against an UNDER_CONSTRUCTION chest`() {
        val halfBuilt = chest(status = BuildingStatus.UNDER_CONSTRUCTION)
        val store = StubBuildings(halfBuilt)

        val result = reduceWithdraw(
            stateAt(), WorldCommand.WithdrawFromChest(agent, halfBuilt.instanceId, wood, 1),
            store, StubChestContents(), tick = 1,
        )

        assertEquals(
            WorldRejection.BuildingNotActive(halfBuilt.instanceId, BuildingStatus.UNDER_CONSTRUCTION),
            result.leftOrNull(),
        )
    }

    private fun itemFor(id: ItemId, weightPerUnit: Int) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = weightPerUnit,
        maxStack = 200,
    )

    private class StubItemLookup(private val map: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = map[id]
        override fun all(): List<Item> = map.values.toList()
    }

    private inner class StubBuildings(vararg seed: Building) : BuildingsStore {
        private val rows: MutableList<Building> = seed.toMutableList()
        override fun insert(building: Building) {
            rows += building
        }
        override fun findById(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? =
            rows.firstOrNull {
                it.nodeId == node && it.builtByAgentId == agent && it.type == type &&
                    it.status == BuildingStatus.UNDER_CONSTRUCTION
            }
        override fun listAtNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? = error("not used")
        override fun complete(id: UUID, asOfTick: Long): Building? = error("not used")
    }

    private class StubChestContents : ChestContentsStore {
        private val map = mutableMapOf<Pair<UUID, ItemId>, Int>()
        override fun quantityOf(buildingId: UUID, item: ItemId): Int = map[buildingId to item] ?: 0
        override fun contentsOf(buildingId: UUID): Map<ItemId, Int> =
            map.entries.filter { it.key.first == buildingId }.associate { it.key.second to it.value }
        override fun add(buildingId: UUID, item: ItemId, quantity: Int) {
            map.merge(buildingId to item, quantity, Int::plus)
        }
        override fun remove(buildingId: UUID, item: ItemId, quantity: Int): Boolean {
            val current = map[buildingId to item] ?: return false
            if (current < quantity) return false
            val next = current - quantity
            if (next == 0) map.remove(buildingId to item) else map[buildingId to item] = next
            return true
        }
    }
}

