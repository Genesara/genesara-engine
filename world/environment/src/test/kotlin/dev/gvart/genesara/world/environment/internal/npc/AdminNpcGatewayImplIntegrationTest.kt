package dev.gvart.genesara.world.environment.internal.npc

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.LootEntry
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.environment.AdminNpcGateway
import dev.gvart.genesara.world.environment.KillOutcome
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NPCS
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class AdminNpcGatewayImplIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("admin_npc_it")
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

    private val wolfType = NpcType("GRAY_WOLF")
    private val wolfDef = NpcDef(
        type = wolfType,
        displayName = "Gray Wolf",
        hpMax = 30,
        damage = 6,
        damageType = DamageType.PIERCE,
        range = 1,
        attackIntervalTicks = 1,
        defense = 0,
        dodgeChancePercent = 0,
        aggressionProfile = AggressionProfile.HOSTILE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = 1,
        fleeDistance = 0,
    )

    private val ranger = AgentId(UUID.randomUUID())

    private lateinit var store: JooqNpcsStore
    private lateinit var gateway: AdminNpcGateway
    private lateinit var publisher: CapturingPublisher
    private lateinit var groundItems: RecordingGroundItemStore
    private var seededNode: NodeId = NodeId(0L)
    private var seededRegionId: RegionId = RegionId(0L)
    private var seededWorldId: Long = 0L

    @BeforeEach
    fun reset() {
        dsl.deleteFrom(NPCS).execute()
        dsl.deleteFrom(NODES).execute()
        dsl.deleteFrom(REGIONS).execute()
        dsl.deleteFrom(WORLDS).execute()
        val (worldVal, regionVal, nodeVal) = seedNode()
        seededWorldId = worldVal
        seededRegionId = RegionId(regionVal)
        seededNode = NodeId(nodeVal)

        store = JooqNpcsStore(dsl)
        publisher = CapturingPublisher()
        groundItems = RecordingGroundItemStore()
        gateway = AdminNpcGatewayImpl(
            store = store,
            catalog = catalogFor(wolfDef),
            lootRoll = lootRollWithSingleWoodDrop(),
            publisher = publisher,
        )
    }

    @Test
    fun `admin-spawned wolf is visible via byNodes (look_around path) and reacts via NpcAiSweep`() {
        val spawned = gateway.spawn(seededNode, wolfType, hp = null, tick = 100L)

        val atNode = store.byNodes(listOf(seededNode))
        assertEquals(1, atNode.size, "admin spawn must be visible via the same byNodes call that powers look_around")
        assertEquals(spawned.id, atNode.single().id)
        publisher.events.filterIsInstance<EnvironmentEvent.NpcSpawned>().single().also {
            assertEquals(spawned.id, it.npc)
            assertEquals(seededNode, it.at)
        }

        val state = WorldState(
            regions = mapOf(seededRegionId to region(seededRegionId)),
            nodes = mapOf(seededNode to Node(seededNode, seededRegionId, 0, 0, Terrain.FOREST, emptySet())),
            positions = mapOf(ranger to seededNode),
            bodies = mapOf(
                ranger to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
            ),
            inventories = emptyMap(),
            npcs = atNode.associateBy { it.id },
        )
        val sweep = NpcAiSweep(catalogFor(wolfDef), balance(), stubAgents(), stubDeathProcessor())

        val (_, events) = sweep.apply(state, tick = 101L, rng = Random(0L))

        val attack = events.filterIsInstance<CombatEvent.NpcAttackedAgent>().singleOrNull()
        assertNotNull(attack, "admin-spawned NPC must attack the same-node agent on the next tick")
        assertEquals(spawned.id, attack.npc)
        assertEquals(ranger, attack.target)
    }

    @Test
    fun `kill silent deletes the row and emits no events`() {
        val spawned = gateway.spawn(seededNode, wolfType, hp = null, tick = 100L)
        publisher.events.clear()

        val outcome = gateway.kill(spawned.id, silent = true, tick = 110L)

        assertEquals(spawned.id, outcome.npc.id)
        assertTrue(outcome.drops.isEmpty())
        assertTrue(store.byNodes(listOf(seededNode)).isEmpty())
        assertTrue(publisher.events.isEmpty())
        assertTrue(groundItems.deposits.isEmpty())
    }

    @Test
    fun `kill non-silent emits NpcDied and runs loot path`() {
        val spawned = gateway.spawn(seededNode, wolfType, hp = null, tick = 100L)
        publisher.events.clear()

        val outcome = gateway.kill(spawned.id, silent = false, tick = 110L)

        assertEquals(1, outcome.drops.size)
        assertTrue(store.byNodes(listOf(seededNode)).isEmpty())

        publisher.events.filterIsInstance<EnvironmentEvent.NpcDied>().single().also {
            assertEquals(spawned.id, it.npc)
            assertEquals(1, it.drops.size)
        }
        assertEquals(1, groundItems.deposits.size)
    }

    private fun seedNode(): Triple<Long, Long, Long> {
        val worldId = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, "admin-npc-it-${System.nanoTime()}")
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!
        val regionId = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, 0)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!
        val nodeId = dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, 0)
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, "FOREST")
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!
        return Triple(worldId, regionId, nodeId)
    }

    private fun region(id: RegionId): Region = Region(
        id = id, worldId = WorldId(seededWorldId), sphereIndex = 0,
        biome = Biome.FOREST, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private fun catalogFor(vararg defs: NpcDef): NpcCatalog {
        val byType = defs.associateBy { it.type }
        return object : NpcCatalog {
            override fun byType(type: NpcType): NpcDef? = byType[type]
            override fun all(): Collection<NpcDef> = byType.values
            override fun byBiome(biome: Biome): List<NpcDef> = byType.values.filter { biome in it.spawnBiomes }
        }
    }

    private fun lootRollWithSingleWoodDrop(): LootRoll =
        LootRoll(
            lootTables = object : LootTableCatalog {
                override fun byMob(mob: NpcType): List<LootEntry> = listOf(
                    LootEntry(item = ItemId("WOOD"), dropChance = 1.0, quantityMin = 1, quantityMax = 1),
                )
                override fun allMobs(): Set<NpcType> = setOf(wolfType)
            },
            items = object : ItemLookup {
                override fun byId(id: ItemId): Item? = if (id.value == "WOOD") woodItem else null
                override fun all(): List<Item> = listOf(woodItem)
            },
            groundItems = groundItems,
            rarityRoller = FixedRarityRoller,
        )

    private val woodItem = Item(
        id = ItemId("WOOD"),
        displayName = "Wood",
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
    )

    private fun stubAgents(): AgentRegistry = object : AgentRegistry {
        override fun find(id: AgentId): Agent? =
            Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "test", attributes = AgentAttributes())
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome =
            DeathPenaltyOutcome(xpLost = 0, deleveled = false, attributePointLost = null)
    }

    private fun stubDeathProcessor(): DeathProcessor = DeathProcessor(
        balance = balance(),
        agents = stubAgents(),
        equipment = stubEquipment(),
        groundItems = groundItems,
    )

    private fun stubEquipment(): AgentItemInstancesStore = object : InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private fun balance(): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun xpLossOnDeath(): Int = 0
        override fun killStreakWindowTicks(): Long = 1000L
        override fun dropChanceForKillCount(killCount: Int): Double = 0.0
    }

    private class CapturingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<WorldEvent>()
        override fun publishEvent(event: Any) {
            if (event is WorldEvent) events += event
        }
        override fun publishEvent(event: ApplicationEvent) { /* unused */ }
    }

    private class RecordingGroundItemStore : GroundItemStore {
        val deposits = mutableListOf<Pair<NodeId, DroppedItemView>>()
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) {
            deposits += node to drop
        }
        override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
    }

    private object FixedRarityRoller : dev.gvart.genesara.world.internal.balance.RarityRoller() {
        override fun roll(skillLevel: Int, luck: Int): Rarity = Rarity.COMMON
    }
}
