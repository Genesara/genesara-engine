package dev.gvart.genesara.world.internal.trade

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
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
import dev.gvart.genesara.world.TradeStatus
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.jooq.tables.references.TRADE_OFFERS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Testcontainers
class TradeFlowIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("trade_flow_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = WorldFlyway.pooledDataSource(postgres)
            WorldFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private lateinit var store: JooqTradeStore

    private val offerer = AgentId(UUID.randomUUID())
    private val recipient = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val items = StubItemLookup(mapOf(wood to itemFor(wood), stone to itemFor(stone)))

    @BeforeEach
    fun reset() {
        dsl.truncate(TRADE_OFFERS).cascade().execute()
        store = JooqTradeStore(dsl, mapper)
    }

    @Test
    fun `offer accept flow persists ACCEPTED and swaps inventories in memory`() {
        val initial = bothAtSameNode(
            offererInventory = mapOf(wood to 10, stone to 5),
            recipientInventory = mapOf(wood to 0, stone to 20),
        )
        val offerCommand = WorldCommand.TradeOffer(
            agent = offerer, recipient = recipient,
            offered = mapOf(wood to 3),
            requested = mapOf(stone to 4),
        )

        val (_, offerEvents) = assertNotNull(
            reduceTradeOffer(initial, offerCommand, FixedBalance, items, TrustingRelationships, store, NoBuildingsLookup, PassiveAuraAggregator.NoAura, LevelScalingAggregator.NoScaling, tick = 1).getOrNull(),
        )
        val received = assertIs<WorldEvent.TradeOfferReceived>(offerEvents.single())
        assertEquals(offerCommand.tradeId, received.tradeId)

        val persisted = assertNotNull(store.find(offerCommand.tradeId))
        assertEquals(TradeStatus.PENDING, persisted.status)
        assertEquals(mapOf(wood to 3), persisted.offered)
        assertEquals(mapOf(stone to 4), persisted.requested)

        val (afterRespond, respondEvents) = assertNotNull(
            reduceTradeRespond(
                initial,
                WorldCommand.TradeRespond(agent = recipient, tradeId = offerCommand.tradeId, accept = true),
                items, store, NoOpTriggeredPassiveDispatcher, NoOpProgression, NoAgents, tick = 2,
            ).getOrNull(),
        )

        assertEquals(7, afterRespond.inventoryOf(offerer).quantityOf(wood))
        assertEquals(9, afterRespond.inventoryOf(offerer).quantityOf(stone))
        assertEquals(3, afterRespond.inventoryOf(recipient).quantityOf(wood))
        assertEquals(16, afterRespond.inventoryOf(recipient).quantityOf(stone))

        val terminal = assertNotNull(store.find(offerCommand.tradeId))
        assertEquals(TradeStatus.ACCEPTED, terminal.status)
        assertEquals(2L, terminal.resolvedAtTick)

        assertIs<WorldEvent.TradeAccepted>(respondEvents.single())
    }

    @Test
    fun `offer reject flow persists REJECTED and leaves inventories untouched`() {
        val initial = bothAtSameNode(
            offererInventory = mapOf(wood to 10),
            recipientInventory = mapOf(stone to 10),
        )
        val offerCommand = WorldCommand.TradeOffer(
            agent = offerer, recipient = recipient,
            offered = mapOf(wood to 2),
            requested = mapOf(stone to 2),
        )
        reduceTradeOffer(initial, offerCommand, FixedBalance, items, TrustingRelationships, store, NoBuildingsLookup, PassiveAuraAggregator.NoAura, LevelScalingAggregator.NoScaling, tick = 1)

        val (afterRespond, _) = assertNotNull(
            reduceTradeRespond(
                initial,
                WorldCommand.TradeRespond(agent = recipient, tradeId = offerCommand.tradeId, accept = false),
                items, store, NoOpTriggeredPassiveDispatcher, NoOpProgression, NoAgents, tick = 2,
            ).getOrNull(),
        )

        assertEquals(10, afterRespond.inventoryOf(offerer).quantityOf(wood))
        assertEquals(10, afterRespond.inventoryOf(recipient).quantityOf(stone))

        val terminal = assertNotNull(store.find(offerCommand.tradeId))
        assertEquals(TradeStatus.REJECTED, terminal.status)
        assertEquals(2L, terminal.resolvedAtTick)
    }

    @Test
    fun `concurrent respond — second findPendingForUpdate sees null after the first resolves`() {
        val initial = bothAtSameNode(
            offererInventory = mapOf(wood to 1),
            recipientInventory = mapOf(stone to 1),
        )
        val offerCommand = WorldCommand.TradeOffer(
            agent = offerer, recipient = recipient,
            offered = mapOf(wood to 1),
            requested = mapOf(stone to 1),
        )
        reduceTradeOffer(initial, offerCommand, FixedBalance, items, TrustingRelationships, store, NoBuildingsLookup, PassiveAuraAggregator.NoAura, LevelScalingAggregator.NoScaling, tick = 1)

        // First respond resolves successfully.
        reduceTradeRespond(
            initial, WorldCommand.TradeRespond(recipient, offerCommand.tradeId, accept = true),
            items, store, NoOpTriggeredPassiveDispatcher, NoOpProgression, NoAgents, tick = 2,
        )

        // Second respond sees the terminal row — forUpdate returns null, reducer falls
        // back to a TradeNotPending diagnosis from `find`.
        assertNull(store.findPendingForUpdate(offerCommand.tradeId))
    }

    private fun bothAtSameNode(
        offererInventory: Map<ItemId, Int>,
        recipientInventory: Map<ItemId, Int>,
    ): WorldState {
        val region = Region(
            id = regionId, worldId = WorldId(1L), sphereIndex = 0,
            biome = Biome.PLAINS, climate = Climate.OCEANIC,
            centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
        )
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())),
            positions = mapOf(offerer to nodeId, recipient to nodeId),
            bodies = mapOf(
                offerer to AgentBody(50, 50, 50, 50, 0, 0),
                recipient to AgentBody(50, 50, 50, 50, 0, 0),
            ),
            inventories = mapOf(offerer to invOf(offererInventory), recipient to invOf(recipientInventory)),
        )
    }

    private fun invOf(stacks: Map<ItemId, Int>): AgentInventory =
        stacks.entries.filter { it.value > 0 }.fold(AgentInventory()) { acc, (item, qty) -> acc.add(item, qty) }

    private fun itemFor(id: ItemId) = Item(
        id = id, displayName = id.value, description = "",
        category = ItemCategory.RESOURCE, weightPerUnit = 100, maxStack = 200,
    )

    private class StubItemLookup(private val map: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = map[id]
        override fun all(): List<Item> = map.values.toList()
    }

    private object TrustingRelationships : RelationshipLookup {
        override fun scoreBetween(a: AgentId, b: AgentId): Int = 100
    }

    private object FixedBalance : BalanceLookup {
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

    private object NoBuildingsLookup : BuildingsLookup {
        override fun byId(id: java.util.UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }

    private object NoOpProgression : SkillProgression {
        override fun accrueXp(agent: AgentId, skill: SkillId, delta: Int, tick: Long, commandId: UUID, classId: AgentClass?) = Unit
    }

    private object NoAgents : AgentRegistry {
        override fun find(id: AgentId): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }
}
