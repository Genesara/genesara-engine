package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.PassiveAuraAggregator.Companion.NoAura
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryPerkCooldownStore
import dev.gvart.genesara.world.internal.testsupport.NoOpActivePerkLookup
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.tick.lease.LeaseLost
import dev.gvart.genesara.world.internal.tick.lease.WorldLeaseFence
import dev.gvart.genesara.world.internal.worldstate.WorldOnlinePresence
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.WorldStateRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher

class WorldTickHandlerTest {

    private val worldId = WorldId(1L)
    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val homeId = NodeId(1L)
    private val northId = NodeId(2L)
    private val ghostId = NodeId(99L)

    private val region = Region(
        id = regionId,
        worldId = worldId,
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )
    private val home = Node(homeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(northId))
    private val north = Node(northId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(homeId))

    private val baseState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(homeId to home, northId to north),
        positions = mapOf(agent to homeId),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 100, stamina = 30, maxStamina = 50, mana = 0, maxMana = 0)),
        inventories = emptyMap(),
    )

    private val balance = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
    }

    private val profiles = object : AgentProfileLookup {
        override fun find(id: AgentId): AgentProfile = AgentProfile(id, maxHp = 100, maxStamina = 50, maxMana = 0)
    }

    private val items: ItemLookup = StubItemLookup()

    @Test
    fun `accepted commands flow through reduce, persist, and publish`() {
        val repo = RecordingRepository(initial = baseState)
        val queue = InMemoryCommandQueue()
        val publisher = RecordingPublisher()

        queue.submit(CoreCommand.MoveAgent(agent, northId), appliesAtTick = 7)
        val handler = newHandler(queue, repo, FixedPresence(setOf(agent)), publisher, balance)

        handler.tickOne(worldId, 7)

        val saved = assertNotNull(repo.lastSaved)
        assertEquals(northId, saved.positions[agent])
        val moved = publisher.events.filterIsInstance<CoreEvent.AgentMoved>().single()
        assertEquals(homeId, moved.from)
        assertEquals(northId, moved.to)
        assertEquals(7L, moved.tick)
    }

    @Test
    fun `rejected commands are skipped, state stays put, and surface as CommandRejected on the stream`() {
        val repo = RecordingRepository(initial = baseState)
        val queue = InMemoryCommandQueue()
        val publisher = RecordingPublisher()

        val cmd = CoreCommand.MoveAgent(agent, ghostId)
        queue.submit(cmd, appliesAtTick = 1)
        val handler = newHandler(queue, repo, FixedPresence(setOf(agent)), publisher, balance)

        handler.tickOne(worldId, 1)

        val saved = assertNotNull(repo.lastSaved)
        assertEquals(homeId, saved.positions[agent])
        assertTrue(publisher.events.none { it is CoreEvent.AgentMoved })
        val rejected = publisher.events.filterIsInstance<CoreEvent.CommandRejected>().single()
        assertEquals(agent, rejected.agent)
        assertEquals(cmd.commandId, rejected.causedBy)
        assertEquals(1L, rejected.tick)
        assertTrue(rejected.kind.isNotBlank())
    }

    @Test
    fun `applyPassives publishes a PassivesApplied event when stamina regenerates`() {
        val below = baseState.copy(
            body = baseState.body.copy(
                bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 100, stamina = 10, maxStamina = 50, mana = 0, maxMana = 0)),
            ),
        )
        val repo = RecordingRepository(initial = below)
        val queue = InMemoryCommandQueue()
        val publisher = RecordingPublisher()
        val regen = object : BalanceLookup {
            override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
            override fun staminaRegenPerTick(climate: Climate) = 1
            override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
            override fun harvestStaminaCost(item: ItemId): Int = 5
            override fun harvestYield(item: ItemId): Int = 1
            override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
            override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
            override fun starvationDamagePerTick(): Int = 0
            override fun isWaterSource(terrain: Terrain): Boolean = false
            override fun drinkStaminaCost(): Int = 1
            override fun drinkThirstRefill(): Int = 25
            override fun sleepRegenPerOfflineTick(): Int = 0
            override fun isTraversable(terrain: Terrain): Boolean = true
        }
        val handler = newHandler(queue, repo, FixedPresence(setOf(agent)), publisher, regen)

        handler.tickOne(worldId, 2)

        val passives = publisher.events.filterIsInstance<BodyEvent.PassivesApplied>().single()
        assertEquals(2L, passives.tick)
        assertEquals(11, repo.lastSaved!!.bodies[agent]!!.stamina)
    }

    @Test
    fun `lease-lost fence aborts the tick before save and before publishing events`() {
        val repo = RecordingRepository(initial = baseState)
        val queue = InMemoryCommandQueue()
        val publisher = RecordingPublisher()
        queue.submit(CoreCommand.MoveAgent(agent, northId), appliesAtTick = 4)

        val handler = newHandler(
            queue, repo, FixedPresence(setOf(agent)), publisher, balance,
            fence = LeaseLostFence,
        )

        assertThrows<LeaseLost> {
            handler.tickOne(worldId, 4)
        }

        assertNull(repo.lastSaved, "save must not run when fence rejects the tick")
        assertTrue(publisher.events.isEmpty(), "no events publish past a lease-lost abort")
    }

    @Test
    fun `spawn resumes the persisted body of an offline agent instead of overwriting with a fresh one`() {
        val persistedBody = AgentBody(
            hp = 5, maxHp = 100,
            stamina = 20, maxStamina = 50,
            mana = 0, maxMana = 0,
            hunger = 80, maxHunger = 100,
            thirst = 80, maxThirst = 100,
            sleep = 80, maxSleep = 100,
        )
        val offlineState = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(homeId to home, northId to north),
            positions = emptyMap(),
            bodies = emptyMap(),
            inventories = emptyMap(),
        )
        val repo = LoadSetAwareRepository(
            base = offlineState,
            persistedBodies = mapOf(agent to persistedBody),
        )
        val queue = InMemoryCommandQueue()
        val publisher = RecordingPublisher()

        queue.submit(CoreCommand.SpawnAgent(agent), appliesAtTick = 3)
        val handler = newHandler(
            queue, repo, FixedPresence(emptySet()), publisher, balance,
            spawnResolver = FixedSpawnResolver(homeId),
        )

        handler.tickOne(worldId, 3)

        val saved = assertNotNull(repo.lastSaved)
        val savedBody = assertNotNull(saved.bodies[agent])
        assertEquals(5, savedBody.hp, "spawn must not overwrite persisted hp with maxHp")
        assertEquals(20, savedBody.stamina)
        assertEquals(homeId, saved.positions[agent])
        val spawned = publisher.events.filterIsInstance<CoreEvent.AgentSpawned>().single()
        assertEquals(agent, spawned.agent)
        assertEquals(homeId, spawned.at)
        assertTrue(repo.lastLoadSet.contains(agent), "the spawning agent must be in the load set")
    }

    @Test
    fun `commands targeted at other ticks are not drained for this tick`() {
        val repo = RecordingRepository(initial = baseState)
        val queue = InMemoryCommandQueue()
        val publisher = RecordingPublisher()
        queue.submit(CoreCommand.MoveAgent(agent, northId), appliesAtTick = 99)
        val handler = newHandler(queue, repo, FixedPresence(setOf(agent)), publisher, balance)

        handler.tickOne(worldId, 7)

        assertTrue(publisher.events.none { it is CoreEvent.AgentMoved })
        assertEquals(1, queue.drainFor(worldId, 99).size)
    }

    private fun newHandler(
        queue: InMemoryCommandQueue,
        repo: WorldStateRepository,
        presence: WorldOnlinePresence,
        publisher: RecordingPublisher,
        balance: BalanceLookup,
        fence: WorldLeaseFence = AlwaysHeldLeaseFence,
        spawnResolver: dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver = NoopSpawnLocationResolver,
    ): WorldTickHandler {
        val lazySpawn = dev.gvart.genesara.world.environment.internal.npc.LazyNpcSpawn(
            catalog = dev.gvart.genesara.world.internal.testsupport.NoOpNpcCatalog,
            balance = balance,
            worldDef = dev.gvart.genesara.world.internal.balance.WorldDefinitionProperties(),
            clearedStore = dev.gvart.genesara.world.internal.testsupport.NoOpNodeClearedTimestampStore,
        )
        val aiSweep = dev.gvart.genesara.world.environment.internal.npc.NpcAiSweep(
            catalog = dev.gvart.genesara.world.internal.testsupport.NoOpNpcCatalog,
            balance = balance,
            agents = NoopAgentRegistry,
            deathProcessor = DeathProcessor(balance, NoopAgentRegistry, NoopEquipmentStore, NoopGroundItemStore),
        )
        return WorldTickHandler(
            queue, repo, presence, publisher, balance, profiles, items, NoopRecipeLookup,
            dev.gvart.genesara.world.AgentKnownRecipesGateway.Empty, NoopResourceStore,
            NoopSkillsRegistry, NoopAgentRegistry, NoopEquipmentStore, NoopSafeNodeGateway,
            NoopSafeNodeResolver, NoopBuildingsStore, NoopBuildingBarsStore, NoopBuildingsLookup, EmptyBuildingsCatalog,
            NoopBuildingGateStateStore,
            NoopChestContentsStore,
            NoopAgentPlotsStore, NoopCropLookup,
            dev.gvart.genesara.world.economy.internal.cultivation.CropDecaySweep(NoopAgentPlotsStore, NoopCropLookup),
            dev.gvart.genesara.world.TradeStore.NoOp, dev.gvart.genesara.world.RelationshipLookup.NoOp,
            dev.gvart.genesara.player.RelationshipsGateway.NoOp,
            NoopRarityRoller, SkillProgression(NoopSkillsRegistry, publisher),
            dev.gvart.genesara.world.internal.classes.CharacterXpProgression.NoOp,
            dev.gvart.genesara.world.RecipeLearning.NoOp,
            NoScaling, NoAura, dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            spawnResolver, NoopGroundItemStore,
            DeathProcessor(balance, NoopAgentRegistry, NoopEquipmentStore, NoopGroundItemStore),
            NoOpTriggeredPassiveDispatcher, NoOpActivePerkLookup, InMemoryPerkCooldownStore(),
            InMemoryPendingAttackScaleStore(), InMemoryBehaviorTracker(),
            dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(),
            npcsStore = dev.gvart.genesara.world.internal.testsupport.NoOpNpcsStore,
            nodeClearedStore = dev.gvart.genesara.world.internal.testsupport.NoOpNodeClearedTimestampStore,
            npcCatalog = dev.gvart.genesara.world.internal.testsupport.NoOpNpcCatalog,
            lootRoll = dev.gvart.genesara.world.environment.internal.npc.NoOpLootRoll,
            lazyNpcSpawn = lazySpawn,
            npcAiSweep = aiSweep,
            leaseFence = fence,
            tickInterval = java.time.Duration.ofSeconds(5L),
        )
    }

    private object AlwaysHeldLeaseFence : WorldLeaseFence {
        override fun requireHeldAndRenew(worldId: WorldId, tick: Long) = Unit
    }

    private object LeaseLostFence : WorldLeaseFence {
        override fun requireHeldAndRenew(worldId: WorldId, tick: Long): Nothing =
            throw LeaseLost(worldId, tick)
    }

    private class RecordingRepository(private val initial: WorldState) : WorldStateRepository {
        var lastSaved: WorldState? = null
        override fun load(worldId: WorldId, onlineAgentIds: Set<AgentId>): WorldState = initial
        override fun save(worldId: WorldId, state: WorldState) {
            lastSaved = state
        }
    }

    private class LoadSetAwareRepository(
        private val base: WorldState,
        private val persistedBodies: Map<AgentId, AgentBody>,
    ) : WorldStateRepository {
        var lastSaved: WorldState? = null
        var lastLoadSet: Set<AgentId> = emptySet()
            private set

        override fun load(worldId: WorldId, onlineAgentIds: Set<AgentId>): WorldState {
            lastLoadSet = onlineAgentIds
            val bodies = persistedBodies.filterKeys { it in onlineAgentIds }
            return base.copy(body = base.body.copy(bodies = base.bodies + bodies))
        }

        override fun save(worldId: WorldId, state: WorldState) {
            lastSaved = state
        }
    }

    private class FixedSpawnResolver(private val node: NodeId) : dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver {
        override fun resolveFor(agentId: AgentId): NodeId = node
    }

    private class FixedPresence(private val agents: Set<AgentId>) : WorldOnlinePresence {
        override fun onlineIn(worldId: WorldId): Set<AgentId> = agents
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class StubItemLookup : ItemLookup {
        override fun byId(id: ItemId): Item? = null
        override fun all(): List<Item> = emptyList()
    }

    private object NoopResourceStore : dev.gvart.genesara.world.internal.resources.NodeResourceStore {
        override fun read(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources.EMPTY
        override fun availability(nodeId: NodeId, item: ItemId, tick: Long) = null
        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {
            error("NoopResourceStore.decrement should not be called in this test")
        }
        override fun seed(rows: Collection<dev.gvart.genesara.world.internal.resources.InitialResourceRow>, tick: Long) {}
    }

    private object NoopSkillsRegistry : dev.gvart.genesara.player.AgentSkillsRegistry {
        override fun snapshot(agent: AgentId) = dev.gvart.genesara.player.AgentSkillsSnapshot(
            perSkill = emptyMap(),
            slotCount = 8,
            slotsFilled = 0,
        )
        override fun addXpIfSlotted(agent: AgentId, skill: dev.gvart.genesara.player.SkillId, delta: Int) = dev.gvart.genesara.player.AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: dev.gvart.genesara.player.SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: dev.gvart.genesara.player.SkillId, slotIndex: Int) = null
    }

    private object NoopAgentRegistry : dev.gvart.genesara.player.AgentRegistry {
        override fun find(id: AgentId): dev.gvart.genesara.player.Agent? = null
        override fun listForOwner(owner: dev.gvart.genesara.account.PlayerId): List<dev.gvart.genesara.player.Agent> = emptyList()
    }

    private object NoopEquipmentStore : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<dev.gvart.genesara.world.EquipSlot, dev.gvart.genesara.world.ItemInstance.Equipment> =
            emptyMap()
        override fun findById(instanceId: UUID): dev.gvart.genesara.world.ItemInstance.Equipment? = error("not used")
        override fun assignToSlot(
            instanceId: UUID,
            agentId: AgentId,
            slot: dev.gvart.genesara.world.EquipSlot,
        ): dev.gvart.genesara.world.ItemInstance.Equipment? = error("not used")
        override fun clearSlot(
            agentId: AgentId,
            slot: dev.gvart.genesara.world.EquipSlot,
        ): dev.gvart.genesara.world.ItemInstance.Equipment? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): dev.gvart.genesara.world.ItemInstance.Equipment? =
            error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }

    private object NoopSafeNodeGateway : dev.gvart.genesara.world.AgentSafeNodeGateway {
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {}
        override fun find(agentId: AgentId): NodeId? = null
        override fun clear(agentId: AgentId) {}
    }

    private object NoopSafeNodeResolver : dev.gvart.genesara.world.internal.death.SafeNodeResolver {
        override fun resolveFor(agentId: AgentId): dev.gvart.genesara.world.internal.death.SafeNodeResolution? = null
    }

    private object NoopSpawnLocationResolver : dev.gvart.genesara.world.internal.spawn.SpawnLocationResolver {
        override fun resolveFor(agentId: AgentId): NodeId? = null
    }

    private object NoopBuildingsStore : dev.gvart.genesara.world.BuildingsStore {
        override fun insert(building: dev.gvart.genesara.world.Building) = error("not used")
        override fun findById(id: java.util.UUID): dev.gvart.genesara.world.Building? = null
        override fun findInProgress(
            node: NodeId,
            agent: AgentId,
            type: dev.gvart.genesara.world.BuildingType,
        ): dev.gvart.genesara.world.Building? = null
        override fun findAnyAtNodeOfType(
            node: NodeId,
            type: dev.gvart.genesara.world.BuildingType,
        ): dev.gvart.genesara.world.Building? = null
        override fun listAtNode(node: NodeId): List<dev.gvart.genesara.world.Building> = emptyList()
        override fun listByNodes(
            nodes: Set<NodeId>,
        ): Map<NodeId, List<dev.gvart.genesara.world.Building>> = emptyMap()
        override fun advanceProgress(id: java.util.UUID, newProgress: Int, asOfTick: Long): dev.gvart.genesara.world.Building? = null
        override fun complete(id: java.util.UUID, asOfTick: Long): dev.gvart.genesara.world.Building? = null
    }

    private object NoopBuildingBarsStore : dev.gvart.genesara.world.BuildingBarsStore {
        override fun insertAll(bars: List<dev.gvart.genesara.world.BuildingBar>) = Unit
        override fun barsByInstance(instanceId: java.util.UUID): List<dev.gvart.genesara.world.BuildingBar> = emptyList()
        override fun barsByInstances(instanceIds: Set<java.util.UUID>): Map<java.util.UUID, List<dev.gvart.genesara.world.BuildingBar>> = emptyMap()
        override fun advanceBar(instanceId: java.util.UUID, skill: dev.gvart.genesara.player.SkillId): dev.gvart.genesara.world.BuildingBar? = null
    }

    private object NoopBuildingsLookup : dev.gvart.genesara.world.BuildingsLookup {
        override fun byId(id: java.util.UUID): dev.gvart.genesara.world.Building? = null
        override fun byNode(node: NodeId): List<dev.gvart.genesara.world.Building> = emptyList()
        override fun byNodes(
            nodes: Set<NodeId>,
        ): Map<NodeId, List<dev.gvart.genesara.world.Building>> = emptyMap()
        override fun activeStationsAt(
            node: NodeId,
            hint: dev.gvart.genesara.world.BuildingCategoryHint,
        ): List<dev.gvart.genesara.world.Building> = emptyList()
    }

    private val EmptyBuildingsCatalog: dev.gvart.genesara.world.environment.internal.buildings.BuildingsCatalog =
        dev.gvart.genesara.world.environment.internal.buildings.BuildingsCatalog(
            dev.gvart.genesara.world.environment.internal.buildings.BuildingDefinitionProperties(catalog = emptyMap()),
        )

    private object NoopChestContentsStore : dev.gvart.genesara.world.ChestContentsStore {
        override fun quantityOf(buildingId: java.util.UUID, item: dev.gvart.genesara.world.ItemId): Int = 0
        override fun contentsOf(buildingId: java.util.UUID): Map<dev.gvart.genesara.world.ItemId, Int> = emptyMap()
        override fun add(buildingId: java.util.UUID, item: dev.gvart.genesara.world.ItemId, quantity: Int) =
            error("not used")
        override fun remove(buildingId: java.util.UUID, item: dev.gvart.genesara.world.ItemId, quantity: Int): Boolean =
            error("not used")
    }

    private object NoopBuildingGateStateStore : dev.gvart.genesara.world.BuildingGateStateStore {
        override fun insertClosed(gateInstanceId: java.util.UUID) = error("not used")
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

    private object NoopAgentPlotsStore : dev.gvart.genesara.world.AgentPlotsStore {
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) = error("not used")
        override fun findById(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> = emptyMap()
        override fun plant(
            plotId: java.util.UUID,
            crop: dev.gvart.genesara.world.PlantedCrop,
        ): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: java.util.UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> = emptyList()
    }

    private object NoopCropLookup : dev.gvart.genesara.world.CropLookup {
        override fun byId(id: dev.gvart.genesara.world.CropId): dev.gvart.genesara.world.Crop? = null
        override fun all(): List<dev.gvart.genesara.world.Crop> = emptyList()
    }

    private object NoopRecipeLookup : dev.gvart.genesara.world.RecipeLookup {
        override fun byId(id: dev.gvart.genesara.world.RecipeId): dev.gvart.genesara.world.Recipe? = null
        override fun all(): List<dev.gvart.genesara.world.Recipe> = emptyList()
    }

    private val NoopRarityRoller: dev.gvart.genesara.world.internal.balance.RarityRoller =
        dev.gvart.genesara.world.internal.balance.RarityRoller(kotlin.random.Random(0))

    private object NoopGroundItemStore : dev.gvart.genesara.world.GroundItemStore {
        override fun deposit(
            node: NodeId,
            drop: dev.gvart.genesara.world.DroppedItemView,
            droppedAtTick: Long,
        ) = error("not used")
        override fun atNode(node: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun take(node: NodeId, dropId: java.util.UUID): dev.gvart.genesara.world.GroundItemView? = null
    }
}
