package dev.gvart.genesara.world.internal.tick

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.PassiveAuraAggregator.Companion.NoAura
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.environment.internal.buildings.BuildingDefinitionProperties
import dev.gvart.genesara.world.environment.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.balance.RarityRoller
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.death.SafeNodeResolution
import dev.gvart.genesara.world.internal.death.SafeNodeResolver
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.killstreaks.KillStreakStore
import dev.gvart.genesara.world.internal.resources.InitialResourceRow
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryPerkCooldownStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache
import dev.gvart.genesara.world.internal.testsupport.NoOpActivePerkLookup
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import dev.gvart.genesara.world.internal.tick.lease.WorldLeaseFence
import dev.gvart.genesara.world.internal.worldstate.JooqWorldOnlinePresence
import dev.gvart.genesara.world.internal.worldstate.JooqWorldStateRepository
import dev.gvart.genesara.world.internal.worldstate.WorldStaticConfig
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

@Testcontainers
class WorldTickHandlerSpawnResumeIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("world_it")
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

    private lateinit var repository: JooqWorldStateRepository
    private lateinit var staticConfig: WorldStaticConfig
    private lateinit var presence: JooqWorldOnlinePresence

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @BeforeEach
    fun resetState() {
        dsl.truncate(AGENT_INVENTORY).cascade().execute()
        dsl.truncate(AGENT_BODIES).cascade().execute()
        dsl.truncate(AGENT_POSITIONS).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()

        staticConfig = WorldStaticConfig(dsl, mapper)
        presence = JooqWorldOnlinePresence(dsl)
        repository = JooqWorldStateRepository(dsl, staticConfig, NoopKillStreakStore, InMemoryVisionBlockerCache())
    }

    @Test
    fun `tickOne resumes a persisted body of an offline agent on spawn instead of overwriting it`() {
        val (worldId, nodeId) = seedSingleNodeWorld("world-spawn-resume")
        staticConfig.reload()

        val agent = AgentId(UUID.randomUUID())
        seedPersistedBody(agent, hp = 5, hunger = 1, thirst = 1)

        assertEquals(emptySet(), presence.onlineIn(worldId), "agent has no active position before spawn")

        val queue = InMemoryCommandQueue()
        val publisher = RecordingPublisher()
        queue.submitTo(worldId, CoreCommand.SpawnAgent(agent), appliesAtTick = 1)

        val handler = newHandler(
            queue = queue,
            publisher = publisher,
            spawnResolver = FixedSpawnResolver(nodeId),
            profileFor = { id -> AgentProfile(id, maxHp = 100, maxStamina = 50, maxMana = 0) },
        )

        handler.tickOne(worldId, 1)

        val reloaded = repository.load(worldId, presence.onlineIn(worldId))
        val savedBody = assertNotNull(reloaded.bodies[agent], "spawn must persist a body for the agent")
        assertEquals(5, savedBody.hp, "spawn must NOT overwrite persisted hp with maxHp (issue #116)")
        assertEquals(1, savedBody.hunger, "persisted hunger survives spawn")
        assertEquals(1, savedBody.thirst, "persisted thirst survives spawn")
        assertEquals(nodeId, reloaded.positions[agent], "agent is positioned at the resolved spawn node")
    }

    private fun seedPersistedBody(agent: AgentId, hp: Int, hunger: Int, thirst: Int) {
        dsl.insertInto(AGENT_BODIES)
            .set(AGENT_BODIES.AGENT_ID, agent.id)
            .set(AGENT_BODIES.HP, hp).set(AGENT_BODIES.MAX_HP, 100)
            .set(AGENT_BODIES.STAMINA, 20).set(AGENT_BODIES.MAX_STAMINA, 50)
            .set(AGENT_BODIES.MANA, 0).set(AGENT_BODIES.MAX_MANA, 0)
            .set(AGENT_BODIES.HUNGER, hunger).set(AGENT_BODIES.MAX_HUNGER, 100)
            .set(AGENT_BODIES.THIRST, thirst).set(AGENT_BODIES.MAX_THIRST, 100)
            .set(AGENT_BODIES.SLEEP, 80).set(AGENT_BODIES.MAX_SLEEP, 100)
            .execute()
    }

    private data class SeededWorld(val worldId: WorldId, val nodeId: NodeId)

    private fun seedSingleNodeWorld(name: String): SeededWorld {
        val worldIdValue = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, name)
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID).fetchOne()!!.value1()!!

        val regionIdValue = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldIdValue)
            .set(REGIONS.SPHERE_INDEX, 0)
            .set(REGIONS.BIOME, "PLAINS")
            .set(REGIONS.CLIMATE, "OCEANIC")
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID).fetchOne()!!.value1()!!

        val nodeIdValue = dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionIdValue)
            .set(NODES.Q, 0).set(NODES.R, 0)
            .set(NODES.TERRAIN, "PLAINS")
            .returningResult(NODES.ID).fetchOne()!!.value1()!!

        return SeededWorld(WorldId(worldIdValue), NodeId(nodeIdValue))
    }

    private fun newHandler(
        queue: InMemoryCommandQueue,
        publisher: ApplicationEventPublisher,
        spawnResolver: SpawnLocationResolver,
        profileFor: (AgentId) -> AgentProfile,
    ): WorldTickHandler {
        val skills: AgentSkillsRegistry = NoopSkillsRegistry
        val balance = ZeroBalanceLookup
        val agents = NoopAgentRegistry
        val equipment = NoopEquipmentStore
        val groundItems = NoopGroundItemStore
        val deathProcessor = DeathProcessor(balance, agents, equipment, groundItems)
        val rarity = RarityRoller(kotlin.random.Random(0))
        val buildingsCatalog = BuildingsCatalog(BuildingDefinitionProperties(catalog = emptyMap()))
        val profiles = object : AgentProfileLookup {
            override fun find(id: AgentId): AgentProfile = profileFor(id)
        }
        val noopProfileRepo = object : dev.gvart.genesara.player.AgentProfileRepository {
            override fun save(profile: dev.gvart.genesara.player.AgentProfile) = Unit
        }
        return WorldTickHandler(
            queue, repository, presence, publisher, balance, profiles, noopProfileRepo, NoopItemLookup,
            NoopRecipeLookup, dev.gvart.genesara.world.AgentKnownRecipesGateway.Empty,
            NoopResourceStore, skills, agents, equipment, NoopSafeNodeGateway,
            NoopSafeNodeResolver, NoopBuildingsStore, NoopBuildingBarsStore, NoopBuildingsLookup, buildingsCatalog,
            NoopBuildingGateStateStore,
            NoopChestContentsStore,
            NoopAgentPlotsStore, NoopCropLookup,
            dev.gvart.genesara.world.economy.internal.cultivation.CropDecaySweep(NoopAgentPlotsStore, NoopCropLookup),
            dev.gvart.genesara.world.TradeStore.NoOp, dev.gvart.genesara.world.RelationshipLookup.NoOp,
            dev.gvart.genesara.player.RelationshipsGateway.NoOp,
            rarity, SkillProgression(skills, publisher),
            CharacterXpProgression.NoOp, dev.gvart.genesara.world.RecipeLearning.NoOp,
            NoScaling, NoAura, dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            spawnResolver, groundItems,
            deathProcessor, NoOpTriggeredPassiveDispatcher, NoOpActivePerkLookup,
            InMemoryPerkCooldownStore(), InMemoryPendingAttackScaleStore(),
            InMemoryBehaviorTracker(), InMemoryVisionBlockerCache(),
            partyStore = dev.gvart.genesara.world.PartyStore.NoOp,
            partyInviteStore = dev.gvart.genesara.world.PartyInviteStore.NoOp,
            partyReadView = dev.gvart.genesara.world.internal.worldstate.views.PartyReadView.NoOp,
            visibleNodes = dev.gvart.genesara.world.VisibleNodes.NoOp,
            clans = dev.gvart.genesara.world.internal.testsupport.NoOpClanRegistry,
            npcsStore = dev.gvart.genesara.world.internal.testsupport.NoOpNpcsStore,
            nodeClearedStore = dev.gvart.genesara.world.internal.testsupport.NoOpNodeClearedTimestampStore,
            npcCatalog = dev.gvart.genesara.world.internal.testsupport.NoOpNpcCatalog,
            lootRoll = dev.gvart.genesara.world.environment.internal.npc.NoOpLootRoll,
            lazyNpcSpawn = dev.gvart.genesara.world.environment.internal.npc.LazyNpcSpawn(
                catalog = dev.gvart.genesara.world.internal.testsupport.NoOpNpcCatalog,
                balance = balance,
                worldDef = dev.gvart.genesara.world.internal.balance.WorldDefinitionProperties(),
                clearedStore = dev.gvart.genesara.world.internal.testsupport.NoOpNodeClearedTimestampStore,
                zoneLookup = dev.gvart.genesara.world.internal.testsupport.NoOpNpcZoneLookup,
            ),
            npcAiSweep = dev.gvart.genesara.world.environment.internal.npc.NpcAiSweep(
                catalog = dev.gvart.genesara.world.internal.testsupport.NoOpNpcCatalog,
                balance = balance,
                agents = agents,
                deathProcessor = deathProcessor,
            ),
            mountDeathCleanup = dev.gvart.genesara.world.environment.internal.mount.MountDeathCleanup(
                instances = dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore(),
                mountInventory = dev.gvart.genesara.world.MountInventoryStore.NoOp,
                groundItems = dev.gvart.genesara.world.internal.testsupport.NoOpGroundItemStore,
            ),
            leaseFence = AlwaysHeldLeaseFence,
            tickInterval = Duration.ofSeconds(5L),
        )
    }

    private object NoopBuildingGateStateStore : dev.gvart.genesara.world.BuildingGateStateStore {
        override fun insertClosed(gateInstanceId: java.util.UUID) = Unit
        override fun isOpen(gateInstanceId: java.util.UUID): Boolean? = null
        override fun toggle(gateInstanceId: java.util.UUID): Boolean? = null
    }

    private object NoopAgentKeysStore : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun agentHoldsKeyFor(
            agent: dev.gvart.genesara.player.AgentId,
            gateInstanceId: java.util.UUID,
        ): Boolean = false
        override fun listByAgent(agent: dev.gvart.genesara.player.AgentId): List<dev.gvart.genesara.world.ItemInstance.Key> =
            emptyList()
    }

    private object AlwaysHeldLeaseFence : WorldLeaseFence {
        override fun requireHeldAndRenew(worldId: WorldId, tick: Long) = Unit
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class FixedSpawnResolver(private val node: NodeId) : SpawnLocationResolver {
        override fun resolveFor(agentId: AgentId): NodeId = node
    }

    private object NoopKillStreakStore : KillStreakStore {
        override fun byAgents(agents: Set<AgentId>): Map<AgentId, AgentKillStreak> = emptyMap()
        override fun save(agent: AgentId, streak: AgentKillStreak) = Unit
        override fun delete(agent: AgentId) = Unit
    }

    private object ZeroBalanceLookup : BalanceLookup {
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
    }

    private object NoopItemLookup : ItemLookup {
        override fun byId(id: ItemId): Item? = null
        override fun all(): List<Item> = emptyList()
    }

    private object NoopRecipeLookup : RecipeLookup {
        override fun byId(id: RecipeId): Recipe? = null
        override fun all(): List<Recipe> = emptyList()
    }

    private object NoopResourceStore : NodeResourceStore {
        override fun read(nodeId: NodeId, tick: Long) = NodeResources.EMPTY
        override fun availability(nodeId: NodeId, item: ItemId, tick: Long) = null
        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) =
            error("not used")
        override fun seed(rows: Collection<InitialResourceRow>, tick: Long) {}
    }

    private object NoopSkillsRegistry : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId) = AgentSkillsSnapshot(
            perSkill = emptyMap(), slotCount = 8, slotsFilled = 0,
        )
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int) = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int) = null
    }

    private object NoopAgentRegistry : AgentRegistry {
        override fun find(id: AgentId): Agent? = null
        override fun listForOwner(owner: dev.gvart.genesara.account.PlayerId): List<Agent> = emptyList()
    }

    private object NoopEquipmentStore : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? =
            error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }

    private object NoopSafeNodeGateway : AgentSafeNodeGateway {
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {}
        override fun find(agentId: AgentId): NodeId? = null
        override fun clear(agentId: AgentId) {}
    }

    private object NoopSafeNodeResolver : SafeNodeResolver {
        override fun resolveFor(agentId: AgentId): SafeNodeResolution? = null
    }

    private object NoopBuildingsStore : BuildingsStore {
        override fun insert(building: Building) = error("not used")
        override fun findById(id: UUID): Building? = null
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? = null
        override fun findAnyAtNodeOfType(node: NodeId, type: BuildingType): Building? = null
        override fun listAtNode(node: NodeId): List<Building> = emptyList()
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? = null
        override fun complete(id: UUID, asOfTick: Long): Building? = null
        override fun update(updated: Building): Building? = error("not used")
        override fun delete(id: UUID): Boolean = error("not used")
    }

    private object NoopBuildingBarsStore : dev.gvart.genesara.world.BuildingBarsStore {
        override fun insertAll(bars: List<dev.gvart.genesara.world.BuildingBar>) = Unit
        override fun barsByInstance(instanceId: UUID): List<dev.gvart.genesara.world.BuildingBar> = emptyList()
        override fun barsByInstances(instanceIds: Set<UUID>): Map<UUID, List<dev.gvart.genesara.world.BuildingBar>> = emptyMap()
        override fun advanceBar(instanceId: UUID, skill: dev.gvart.genesara.player.SkillId): dev.gvart.genesara.world.BuildingBar? = null
    }

    private object NoopBuildingsLookup : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }

    private object NoopChestContentsStore : ChestContentsStore {
        override fun quantityOf(buildingId: UUID, item: ItemId): Int = 0
        override fun contentsOf(buildingId: UUID): Map<ItemId, Int> = emptyMap()
        override fun add(buildingId: UUID, item: ItemId, quantity: Int) = error("not used")
        override fun remove(buildingId: UUID, item: ItemId, quantity: Int): Boolean = error("not used")
        override fun replace(buildingId: UUID, contents: Map<ItemId, Int>): Unit = error("not used")
        override fun removeAll(buildingId: UUID, item: ItemId): Boolean = error("not used")
    }

    private object NoopAgentPlotsStore : dev.gvart.genesara.world.AgentPlotsStore {
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) = error("not used")
        override fun findById(plotId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> = emptyMap()
        override fun plant(plotId: UUID, crop: dev.gvart.genesara.world.PlantedCrop): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> = emptyList()
    }

    private object NoopCropLookup : dev.gvart.genesara.world.CropLookup {
        override fun byId(id: dev.gvart.genesara.world.CropId): dev.gvart.genesara.world.Crop? = null
        override fun all(): List<dev.gvart.genesara.world.Crop> = emptyList()
    }

    private object NoopGroundItemStore : GroundItemStore {
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = error("not used")
        override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
    }
}
