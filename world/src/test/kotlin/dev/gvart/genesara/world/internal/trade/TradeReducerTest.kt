package dev.gvart.genesara.world.internal.trade

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.RelationshipLookup
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.TradeOffer
import dev.gvart.genesara.world.TradeStatus
import dev.gvart.genesara.world.TradeStore
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradeReducerTest {

    private val offerer = AgentId(UUID.randomUUID())
    private val recipient = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val otherNodeId = NodeId(2L)
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val unknown = ItemId("XENOMETAL")

    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.PLAINS, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private val items = StubItemLookup(
        mapOf(
            wood to itemFor(wood),
            stone to itemFor(stone),
        ),
    )

    // Override the production-tuned thresholds with small numbers so the trust-gate
    // tests can exercise both sides of the boundary with single-digit quantities.
    private val balance: BalanceLookup = object : BalanceLookup {
        override fun trustGateValueThreshold(): Int = 5
        override fun trustGateRelationshipThreshold(): Int = 25
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 1
        override fun resourceSpawnsFor(terrain: Terrain) = emptyList<dev.gvart.genesara.world.ResourceSpawnRule>()
        override fun harvestStaminaCost(item: ItemId): Int = 1
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 1
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 1
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 1
        override fun sleepRegenPerOfflineTick(): Int = 1
        override fun isTraversable(terrain: Terrain): Boolean = true
    }

    private fun stateWith(
        offererAt: NodeId? = nodeId,
        recipientAt: NodeId? = nodeId,
        offererInventory: Map<ItemId, Int> = mapOf(wood to 10, stone to 10),
        recipientInventory: Map<ItemId, Int> = mapOf(wood to 10, stone to 10),
    ): WorldState {
        val positions = buildMap {
            offererAt?.let { put(offerer, it) }
            recipientAt?.let { put(recipient, it) }
        }
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(
                nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
                otherNodeId to Node(otherNodeId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
            ),
            positions = positions,
            bodies = mapOf(
                offerer to AgentBody(50, 50, 50, 50, 0, 0),
                recipient to AgentBody(50, 50, 50, 50, 0, 0),
            ),
            inventories = mapOf(
                offerer to invOf(offererInventory),
                recipient to invOf(recipientInventory),
            ),
        )
    }

    private fun invOf(stacks: Map<ItemId, Int>): AgentInventory =
        stacks.entries.fold(AgentInventory()) { acc, (item, qty) -> acc.add(item, qty) }

    // ─────────────────────── trade_offer ───────────────────────

    @Test
    fun `offer succeeds — store has PENDING row, inventories unchanged, TradeOfferReceived to both`() {
        val store = FakeTradeStore()
        val rel = FakeRelationships()
        val command = WorldCommand.TradeOffer(
            agent = offerer, recipient = recipient,
            offered = mapOf(wood to 2), requested = mapOf(stone to 2),
        )

        val (next, events) = assertNotNull(
            reduceTradeOffer(stateWith(), command, balance, items, rel, store, NoBuildingsLookup, tick = 5).getOrNull(),
        )

        assertEquals(10, next.inventoryOf(offerer).quantityOf(wood), "inventory should not change at offer time")
        assertEquals(TradeStatus.PENDING, store.byId(command.tradeId)?.status)
        val received = assertIs<WorldEvent.TradeOfferReceived>(events.single())
        assertEquals(command.tradeId, received.tradeId)
        assertEquals(setOf(offerer, recipient), received.listeners)
        assertEquals(mapOf(wood to 2), received.offered)
        assertEquals(mapOf(stone to 2), received.requested)
    }

    @Test
    fun `offer rejects when recipient is the offerer`() {
        val command = WorldCommand.TradeOffer(offerer, offerer, mapOf(wood to 1), mapOf(stone to 1))

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(), FakeTradeStore(), NoBuildingsLookup, 1)

        assertEquals(WorldRejection.CannotTradeWithSelf(offerer), result.leftOrNull())
    }

    @Test
    fun `offer rejects when both sides are empty`() {
        val command = WorldCommand.TradeOffer(offerer, recipient, emptyMap(), emptyMap())

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(), FakeTradeStore(), NoBuildingsLookup, 1)

        assertEquals(WorldRejection.TradeOfferEmpty(offerer), result.leftOrNull())
    }

    @Test
    fun `offer rejects on non-positive quantity`() {
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(wood to 0), mapOf(stone to 1))

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(), FakeTradeStore(), NoBuildingsLookup, 1)

        assertEquals(WorldRejection.NonPositiveQuantity(offerer, 0), result.leftOrNull())
    }

    @Test
    fun `offer rejects when parties are on different nodes`() {
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(wood to 1), mapOf(stone to 1))

        val result = reduceTradeOffer(
            stateWith(recipientAt = otherNodeId),
            command, balance, items, FakeRelationships(), FakeTradeStore(), NoBuildingsLookup, 1,
        )

        val rejection = assertIs<WorldRejection.TradePartnerNotInSameNode>(result.leftOrNull())
        assertEquals(offerer, rejection.actor)
        assertEquals(recipient, rejection.partner)
        assertEquals(nodeId, rejection.actorAt)
        assertEquals(otherNodeId, rejection.partnerAt)
    }

    @Test
    fun `offer rejects when offerer is not in the world`() {
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(wood to 1), mapOf(stone to 1))

        val result = reduceTradeOffer(
            stateWith(offererAt = null),
            command, balance, items, FakeRelationships(), FakeTradeStore(), NoBuildingsLookup, 1,
        )

        assertEquals(WorldRejection.NotInWorld(offerer), result.leftOrNull())
    }

    @Test
    fun `offer rejects when offered item is unknown to the catalog`() {
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(unknown to 1), mapOf(stone to 1))

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(), FakeTradeStore(), NoBuildingsLookup, 1)

        assertEquals(WorldRejection.UnknownItem(unknown), result.leftOrNull())
    }

    @Test
    fun `offer rejects when offerer lacks the offered stock`() {
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(wood to 99), mapOf(stone to 1))

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(), FakeTradeStore(), NoBuildingsLookup, 1)

        assertEquals(WorldRejection.ItemNotInInventory(offerer, wood), result.leftOrNull())
    }

    @Test
    fun `trust gate blocks high-value trade between low-relationship pair`() {
        // value threshold default = 5, relationship threshold default = 25.
        // 4 + 4 = 8 > 5 triggers the gate; score 0 < 25 rejects.
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(wood to 4), mapOf(stone to 4))

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(0), FakeTradeStore(), NoBuildingsLookup, 1)

        val rejection = assertIs<WorldRejection.InsufficientTrust>(result.leftOrNull())
        assertEquals(8, rejection.value)
        assertEquals(5, rejection.valueThreshold)
        assertEquals(0, rejection.relationshipScore)
        assertEquals(25, rejection.relationshipThreshold)
    }

    @Test
    fun `trust gate passes when relationship clears the threshold`() {
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(wood to 4), mapOf(stone to 4))

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(50), FakeTradeStore(), NoBuildingsLookup, 1)

        assertNotNull(result.getOrNull())
    }

    @Test
    fun `trust gate stays off at exactly the value threshold`() {
        // value = 5 == threshold; not strictly greater so gate doesn't engage.
        val command = WorldCommand.TradeOffer(offerer, recipient, mapOf(wood to 3), mapOf(stone to 2))

        val result = reduceTradeOffer(stateWith(), command, balance, items, FakeRelationships(0), FakeTradeStore(), NoBuildingsLookup, 1)

        assertNotNull(result.getOrNull())
    }

    // ─────────────────────── trade_respond ───────────────────────

    @Test
    fun `respond accept swaps inventories and marks ACCEPTED`() {
        val store = FakeTradeStore()
        store.create(
            TradeOffer(
                tradeId = UUID.randomUUID(),
                offerer = offerer, recipient = recipient,
                offered = mapOf(wood to 3), requested = mapOf(stone to 4),
                status = TradeStatus.PENDING, openedAtTick = 1, resolvedAtTick = null,
            ),
        )
        val trade = store.allByStatus(TradeStatus.PENDING).single()

        val (next, events) = assertNotNull(
            reduceTradeRespond(
                stateWith(), WorldCommand.TradeRespond(recipient, trade.tradeId, accept = true),
                items, store, tick = 9,
            ).getOrNull(),
        )

        assertEquals(7, next.inventoryOf(offerer).quantityOf(wood))
        assertEquals(14, next.inventoryOf(offerer).quantityOf(stone))
        assertEquals(13, next.inventoryOf(recipient).quantityOf(wood))
        assertEquals(6, next.inventoryOf(recipient).quantityOf(stone))
        assertEquals(TradeStatus.ACCEPTED, store.byId(trade.tradeId)?.status)
        assertEquals(9L, store.byId(trade.tradeId)?.resolvedAtTick)
        val accepted = assertIs<WorldEvent.TradeAccepted>(events.single())
        assertEquals(setOf(offerer, recipient), accepted.listeners)
    }

    @Test
    fun `respond reject leaves inventories alone and marks REJECTED`() {
        val store = FakeTradeStore()
        store.create(pending(offered = mapOf(wood to 1), requested = mapOf(stone to 1)))
        val trade = store.allByStatus(TradeStatus.PENDING).single()

        val (next, events) = assertNotNull(
            reduceTradeRespond(
                stateWith(), WorldCommand.TradeRespond(recipient, trade.tradeId, accept = false),
                items, store, tick = 4,
            ).getOrNull(),
        )

        assertEquals(10, next.inventoryOf(offerer).quantityOf(wood))
        assertEquals(10, next.inventoryOf(recipient).quantityOf(stone))
        assertEquals(TradeStatus.REJECTED, store.byId(trade.tradeId)?.status)
        assertIs<WorldEvent.TradeRejected>(events.single())
    }

    @Test
    fun `respond on missing trade surfaces TradeNotFound`() {
        val phantom = UUID.randomUUID()

        val result = reduceTradeRespond(
            stateWith(), WorldCommand.TradeRespond(recipient, phantom, accept = true),
            items, FakeTradeStore(), tick = 1,
        )

        assertEquals(WorldRejection.TradeNotFound(phantom), result.leftOrNull())
    }

    @Test
    fun `respond on resolved trade surfaces TradeNotPending carrying the terminal status`() {
        val store = FakeTradeStore()
        store.create(pending(offered = mapOf(wood to 1), requested = mapOf(stone to 1)))
        val trade = store.allByStatus(TradeStatus.PENDING).single()
        // Pre-resolve the trade so a second respond races against terminal state.
        store.markResolved(trade.tradeId, TradeStatus.ACCEPTED, resolvedAtTick = 1)

        val result = reduceTradeRespond(
            stateWith(), WorldCommand.TradeRespond(recipient, trade.tradeId, accept = true),
            items, store, tick = 2,
        )

        assertEquals(
            WorldRejection.TradeNotPending(trade.tradeId, TradeStatus.ACCEPTED),
            result.leftOrNull(),
        )
    }

    @Test
    fun `respond by non-recipient is rejected and the trade stays PENDING`() {
        val store = FakeTradeStore()
        store.create(pending(offered = mapOf(wood to 1), requested = mapOf(stone to 1)))
        val trade = store.allByStatus(TradeStatus.PENDING).single()
        val interloper = AgentId(UUID.randomUUID())

        val result = reduceTradeRespond(
            stateWith(), WorldCommand.TradeRespond(interloper, trade.tradeId, accept = true),
            items, store, tick = 1,
        )

        assertEquals(WorldRejection.NotTradeRecipient(interloper, trade.tradeId), result.leftOrNull())
        assertEquals(TradeStatus.PENDING, store.byId(trade.tradeId)?.status)
    }

    @Test
    fun `respond rejects when offerer drifted off-node before resolution`() {
        val store = FakeTradeStore()
        store.create(pending(offered = mapOf(wood to 1), requested = mapOf(stone to 1)))
        val trade = store.allByStatus(TradeStatus.PENDING).single()

        val result = reduceTradeRespond(
            stateWith(offererAt = otherNodeId),
            WorldCommand.TradeRespond(recipient, trade.tradeId, accept = true),
            items, store, tick = 1,
        )

        val rejection = assertIs<WorldRejection.TradePartnerNotInSameNode>(result.leftOrNull())
        assertEquals(recipient, rejection.actor)
        assertEquals(offerer, rejection.partner)
        assertEquals(TradeStatus.PENDING, store.byId(trade.tradeId)?.status, "the trade should still be open for retry")
    }

    @Test
    fun `respond rejects when offerer no longer has the offered stock`() {
        val store = FakeTradeStore()
        store.create(pending(offered = mapOf(wood to 99), requested = mapOf(stone to 1)))
        val trade = store.allByStatus(TradeStatus.PENDING).single()

        val result = reduceTradeRespond(
            stateWith(), WorldCommand.TradeRespond(recipient, trade.tradeId, accept = true),
            items, store, tick = 1,
        )

        assertEquals(WorldRejection.ItemNotInInventory(offerer, wood), result.leftOrNull())
    }

    @Test
    fun `respond rejects when recipient lacks the requested stock`() {
        val store = FakeTradeStore()
        store.create(pending(offered = mapOf(wood to 1), requested = mapOf(stone to 99)))
        val trade = store.allByStatus(TradeStatus.PENDING).single()

        val result = reduceTradeRespond(
            stateWith(), WorldCommand.TradeRespond(recipient, trade.tradeId, accept = true),
            items, store, tick = 1,
        )

        assertEquals(WorldRejection.ItemNotInInventory(recipient, stone), result.leftOrNull())
    }

    @Test
    fun `respond reject does not need co-location to close the trade`() {
        val store = FakeTradeStore()
        store.create(pending(offered = mapOf(wood to 1), requested = mapOf(stone to 1)))
        val trade = store.allByStatus(TradeStatus.PENDING).single()

        val result = reduceTradeRespond(
            stateWith(offererAt = otherNodeId),
            WorldCommand.TradeRespond(recipient, trade.tradeId, accept = false),
            items, store, tick = 1,
        )

        assertTrue(result.isRight(), "rejecting should succeed even when the offerer wandered off")
        assertEquals(TradeStatus.REJECTED, store.byId(trade.tradeId)?.status)
    }

    private fun pending(offered: Map<ItemId, Int>, requested: Map<ItemId, Int>) = TradeOffer(
        tradeId = UUID.randomUUID(),
        offerer = offerer, recipient = recipient,
        offered = offered, requested = requested,
        status = TradeStatus.PENDING, openedAtTick = 1, resolvedAtTick = null,
    )

    private fun itemFor(id: ItemId) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 200,
    )

    private class StubItemLookup(private val map: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = map[id]
        override fun all(): List<Item> = map.values.toList()
    }

    private class FakeRelationships(private val score: Int = 0) : RelationshipLookup {
        override fun scoreBetween(a: AgentId, b: AgentId): Int = score
    }

    private class FakeTradeStore : TradeStore {
        private val rows = mutableMapOf<UUID, TradeOffer>()

        override fun create(offer: TradeOffer) {
            check(rows.put(offer.tradeId, offer) == null) { "duplicate tradeId ${offer.tradeId}" }
        }

        override fun find(tradeId: UUID): TradeOffer? = rows[tradeId]

        override fun findPendingForUpdate(tradeId: UUID): TradeOffer? =
            rows[tradeId]?.takeIf { it.status == TradeStatus.PENDING }

        override fun markResolved(tradeId: UUID, status: TradeStatus, resolvedAtTick: Long): Boolean {
            val existing = rows[tradeId] ?: return false
            if (existing.status != TradeStatus.PENDING) return false
            rows[tradeId] = existing.copy(status = status, resolvedAtTick = resolvedAtTick)
            return true
        }

        fun byId(tradeId: UUID): TradeOffer? = rows[tradeId]
        fun allByStatus(status: TradeStatus): List<TradeOffer> = rows.values.filter { it.status == status }
    }

    private object NoBuildingsLookup : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }
}
