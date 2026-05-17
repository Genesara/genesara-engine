package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingBar
import dev.gvart.genesara.world.BuildingBarsStore
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class BuildReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val carpentry = SkillId("CARPENTRY")
    private val tracker = InMemoryBehaviorTracker()

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private val campfireDef = BuildingProperties(
        staminaPerStep = 8,
        hp = 30,
        categoryHint = BuildingCategoryHint.COOKING,
        skillBars = mapOf(
            "CARPENTRY" to BarProperties(steps = 5, materialsPerStep = mapOf("WOOD" to 2, "STONE" to 1)),
        ),
    )
    private val shelterDef = BuildingProperties(
        staminaPerStep = 8,
        hp = 80,
        categoryHint = BuildingCategoryHint.RESIDENCE,
        skillBars = mapOf(
            "CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 2)),
        ),
    )

    private val catalog = BuildingsCatalog(
        BuildingDefinitionProperties(catalog = mapOf("CAMPFIRE" to campfireDef, "SHELTER" to shelterDef)),
    )

    private fun stateWith(
        positioned: Boolean = true,
        stamina: Int = 50,
        inventory: Map<ItemId, Int> = mapOf(wood to 100, stone to 100),
    ): WorldState {
        var inv = AgentInventory()
        for ((item, qty) in inventory) inv = inv.add(item, qty)
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())),
            positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
            bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = stamina, maxStamina = 50, mana = 0, maxMana = 0)),
            inventories = mapOf(agent to inv),
        )
    }

    @Test
    fun `first call inserts the building UNDER_CONSTRUCTION at progress 1 and emits BuildingProgressed step 1`() {
        val state = stateWith()
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val safeNodes = StubSafeNodes()
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val command = EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE)

        val out = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, command,
                catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 7,
            ).getOrNull(),
        )
        val next = state.copy(environment = out.sliceDelta).applyEffects(out.effects)
        val events = out.events

        val progressed = assertIs<EnvironmentEvent.BuildingProgressed>(events.single())
        assertEquals(1, progressed.step)
        assertEquals(5, progressed.totalSteps)
        assertEquals(BuildingType.CAMPFIRE, progressed.type)
        assertEquals(nodeId, progressed.at)
        assertEquals(agent, progressed.agent)
        assertEquals(7L, progressed.tick)
        assertEquals(command.commandId, progressed.causedBy)
        assertEquals(1, store.inserted.size)
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, store.rows.single().status)
        assertEquals(progressed.instanceId, store.rows.single().instanceId)
        // step 1 of CAMPFIRE: floor(10/5)=2 wood, floor(5/5)=1 stone
        assertEquals(98, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(99, next.inventoryOf(agent).quantityOf(stone))
        assertEquals(42, next.bodyOf(agent)!!.stamina)
    }

    @Test
    fun `subsequent call advances an existing in-progress instance by one step and emits BuildingProgressed`() {
        val state = stateWith()
        val existing = sampleBuilding(progress = 2)
        val store = StubBuildingsStore(rows = mutableListOf(existing))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val safeNodes = StubSafeNodes()
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val command = EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE)

        val out = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, command,
                catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 9,
            ).getOrNull(),
        )
        val next = state.copy(environment = out.sliceDelta).applyEffects(out.effects)
        val events = out.events

        val progressed = assertIs<EnvironmentEvent.BuildingProgressed>(events.single())
        assertEquals(3, progressed.step)
        assertEquals(5, progressed.totalSteps)
        assertEquals(existing.instanceId, progressed.instanceId)
        assertEquals(9L, progressed.tick)
        assertEquals(command.commandId, progressed.causedBy)
        assertEquals(1, store.advanced.size)
        assertEquals(0, store.completed.size)
        assertEquals(98, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(99, next.inventoryOf(agent).quantityOf(stone))
    }

    @Test
    fun `terminal step flips status to ACTIVE and emits BuildingConstructed`() {
        val state = stateWith()
        val nearlyDone = sampleBuilding(progress = 4)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val command = EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE)

        val events = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, command,
                catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 11,
            ).getOrNull(),
        ).events

        val completed = assertIs<EnvironmentEvent.BuildingConstructed>(events.single())
        assertEquals(nearlyDone.instanceId, completed.instanceId)
        assertEquals(BuildingType.CAMPFIRE, completed.type)
        assertEquals(nodeId, completed.at)
        assertEquals(5, completed.totalSteps)
        assertEquals(11L, completed.tick)
        assertEquals(command.commandId, completed.causedBy)
        assertEquals(BuildingStatus.ACTIVE, store.rows.single().status)
        assertEquals(0, store.advanced.size)
        assertEquals(1, store.completed.size)
    }

    @Test
    fun `SHELTER completion sets the builder's safe node to the shelter's node`() {
        val state = stateWith(inventory = mapOf(wood to 10))
        val nearlyDone = sampleBuilding(type = BuildingType.SHELTER, progress = 1, totalSteps = 2, hp = 80)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val safeNodes = StubSafeNodes()
        val skills = StubSkillsRegistry()

        reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.SHELTER),
            catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 11,
        )

        assertEquals(nodeId, safeNodes.set[agent])
    }

    @Test
    fun `non-SHELTER completion leaves safe node untouched`() {
        val state = stateWith()
        val nearlyDone = sampleBuilding(progress = 4)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val safeNodes = StubSafeNodes()
        val skills = StubSkillsRegistry()

        reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 11,
        )

        assertEquals(emptyMap(), safeNodes.set)
    }

    @Test
    fun `FARM_PLOT completion inserts an empty plot row mapped to the new building`() {
        val farmPlotDef = BuildingProperties(
            staminaPerStep = 8,
            hp = 25,
            categoryHint = BuildingCategoryHint.AGRICULTURE,
            skillBars = mapOf(
                "CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 2)),
            ),
        )
        val plotCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(catalog = mapOf("FARM_PLOT" to farmPlotDef)),
        )
        val state = stateWith(inventory = mapOf(wood to 10))
        val nearlyDone = sampleBuilding(type = BuildingType.FARM_PLOT, progress = 1, totalSteps = 2, hp = 25)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val barsStore = StubBuildingBarsStore().also {
            it.storeRef = store
            it.catalogRef = plotCatalog
        }
        val plots = RecordingAgentPlotsStore()
        val skills = StubSkillsRegistry()

        reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.FARM_PLOT),
            plotCatalog, skills, store, barsStore, StubSafeNodes(), plots, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 11,
        )

        val inserted = plots.inserted.single()
        assertEquals(nearlyDone.instanceId, inserted.buildingInstanceId)
        assertEquals(nodeId, inserted.nodeId)
        assertNull(inserted.plant)
    }

    @Test
    fun `GATE completion inserts a CLOSED gate state row, issues one key to the builder, and emits GateKeyMinted`() {
        val gateDef = BuildingProperties(
            staminaPerStep = 1,
            hp = 120,
            categoryHint = BuildingCategoryHint.DEFENSIVE,
            skillBars = mapOf(
                "CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 1)),
            ),
        )
        val gateCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(catalog = mapOf("GATE" to gateDef)),
        )
        val state = stateWith(inventory = mapOf(wood to 5))
        // Pre-place a near-complete gate row so the next step finishes it.
        val nearlyDone = sampleBuilding(type = BuildingType.GATE, progress = 1, totalSteps = 2, hp = 120)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val barsStore = StubBuildingBarsStore().also {
            it.storeRef = store
            it.catalogRef = gateCatalog
        }
        val gateStates = RecordingGateStates()
        val keys = RecordingAgentKeys()

        val events = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.GATE),
                gateCatalog, StubSkillsRegistry(), store, barsStore, StubSafeNodes(),
                NoOpAgentPlotsStore, gateStates, keys,
                SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
                triggeredPassives = NoOpTriggeredPassiveDispatcher,
                behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 12L,
            ).getOrNull(),
        ).events

        val placed = store.rows.single()
        assertEquals(BuildingStatus.ACTIVE, placed.status)
        assertEquals(BuildingType.GATE, placed.type)
        assertEquals(listOf(placed.instanceId), gateStates.insertedClosed)
        val issuedKey = keys.insertedKeys.single()
        assertEquals(placed.instanceId, issuedKey.gateInstanceId)
        assertEquals(agent, issuedKey.agentId)
        val minted = events.filterIsInstance<EnvironmentEvent.GateKeyMinted>().single()
        assertEquals(issuedKey.instanceId, minted.keyInstanceId)
        assertEquals(placed.instanceId, minted.gateId)
        assertEquals(false, minted.byCopy)
    }

    @Test
    fun `building a DEFENSIVE structure on a node that already has one is rejected`() {
        val gateDef = BuildingProperties(
            staminaPerStep = 11, hp = 120, categoryHint = BuildingCategoryHint.DEFENSIVE,
            skillBars = mapOf("CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 1))),
        )
        val wallDef = BuildingProperties(
            staminaPerStep = 10, hp = 120, categoryHint = BuildingCategoryHint.DEFENSIVE,
            skillBars = mapOf("CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 1))),
        )
        val defensiveCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(catalog = mapOf("GATE" to gateDef, "WOODEN_WALL" to wallDef)),
        )
        val state = stateWith(inventory = mapOf(wood to 5))
        val existingWall = sampleBuilding(type = BuildingType.WOODEN_WALL, totalSteps = 2, progress = 2).copy(
            status = BuildingStatus.ACTIVE,
        )
        val store = StubBuildingsStore(rows = mutableListOf(existingWall))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = defensiveCatalog }
        val skills = StubSkillsRegistry()

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.GATE),
            defensiveCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore,
            NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.DefensiveAlreadyAtNode>(result.leftOrNull())
        assertEquals(BuildingType.WOODEN_WALL, rejection.existingType)
        assertEquals(existingWall.instanceId, rejection.existingInstanceId)
    }

    @Test
    fun `MINE on a non-rocky terrain is rejected with BuildingTerrainMismatch`() {
        val mineDef = BuildingProperties(
            staminaPerStep = 12, hp = 80, categoryHint = BuildingCategoryHint.EXTRACTION_MINE,
            skillBars = mapOf("CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 1))),
        )
        val mineCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(catalog = mapOf("MINE" to mineDef)),
        )
        val state = stateWith(inventory = mapOf(wood to 5))
        // Default stateWith() puts the agent on a PLAINS node — outside the allowed set.
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = mineCatalog }
        val skills = StubSkillsRegistry()

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.MINE),
            mineCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore,
            NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.BuildingTerrainMismatch>(result.leftOrNull())
        assertEquals(BuildingType.MINE, rejection.type)
        assertEquals(Terrain.FOREST, rejection.terrain)
        assertTrue(Terrain.MOUNTAIN in rejection.allowed)
    }

    @Test
    fun `non-FARM_PLOT completion does not insert a plot row`() {
        val state = stateWith()
        val nearlyDone = sampleBuilding(progress = 4)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val plots = RecordingAgentPlotsStore()
        val skills = StubSkillsRegistry()

        reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), plots, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 11,
        )

        assertTrue(plots.inserted.isEmpty())
    }

    private class RecordingAgentPlotsStore : dev.gvart.genesara.world.AgentPlotsStore {
        val inserted = mutableListOf<dev.gvart.genesara.world.AgentPlot>()
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) { inserted += plot }
        override fun findById(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> = emptyMap()
        override fun plant(plotId: java.util.UUID, crop: dev.gvart.genesara.world.PlantedCrop): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: java.util.UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> = emptyList()
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val state = stateWith(positioned = false)
        val skills = StubSkillsRegistry()
        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when stamina is below the per-step cost`() {
        val state = stateWith(stamina = 3)
        val skills = StubSkillsRegistry()
        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        assertEquals(WorldRejection.NotEnoughStamina(agent, required = 8, available = 3), result.leftOrNull())
    }

    @Test
    fun `rejects with InsufficientMaterials and reports the missing item`() {
        // Step 1 of CAMPFIRE needs 2 wood + 1 stone. Agent has wood but no stone, so the
        // single-missing case pins exactly which material the rejection names.
        val state = stateWith(inventory = mapOf(wood to 100))
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.InsufficientMaterials>(result.leftOrNull())
        assertEquals(BuildingType.CAMPFIRE, rejection.type)
        assertEquals(stone, rejection.item)
        assertEquals(1, rejection.required)
        assertEquals(0, rejection.available)
        assertTrue(store.inserted.isEmpty(), "no row inserted on a rejected step")
    }

    @Test
    fun `two agents submitting build of the same type at the same node within one tick — first wins, second is rejected with DuplicateBuildingAtNode`() {
        val agentB = AgentId(UUID.randomUUID())
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())),
            positions = mapOf(agent to nodeId, agentB to nodeId),
            bodies = mapOf(
                agent to AgentBody(50, 50, 50, 50, 0, 0),
                agentB to AgentBody(50, 50, 50, 50, 0, 0),
            ),
            inventories = mapOf(
                agent to AgentInventory().add(wood, 100).add(stone, 100),
                agentB to AgentInventory().add(wood, 100).add(stone, 100),
            ),
        )
        val chestCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    "STORAGE_CHEST" to BuildingProperties(
                        staminaPerStep = 1,
                        hp = 40,
                        categoryHint = BuildingCategoryHint.STORAGE,
                        skillBars = mapOf(
                            "CARPENTRY" to BarProperties(steps = 8, materialsPerStep = mapOf("WOOD" to 2)),
                        ),
                        chestCapacityGrams = 50_000,
                    ),
                ),
            ),
        )
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()

        val outA = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.STORAGE_CHEST),
                chestCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
                triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 100,
            ).getOrNull(),
        )
        val afterA = state.copy(environment = outA.sliceDelta).applyEffects(outA.effects)
        assertIs<EnvironmentEvent.BuildingProgressed>(outA.events.single())

        val rejection = reduceBuild(
            afterA.environment, afterA.body, afterA.core, EnvironmentCommand.BuildStructure(agentB, BuildingType.STORAGE_CHEST),
            chestCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 100,
        ).leftOrNull()

        val duplicate = assertIs<WorldRejection.DuplicateBuildingAtNode>(rejection)
        assertEquals(agentB, duplicate.agent)
        assertEquals(BuildingType.STORAGE_CHEST, duplicate.type)
        assertEquals(nodeId, duplicate.node)
        assertEquals(store.rows.single().instanceId, duplicate.existingInstanceId)
        assertEquals(1, store.rows.size)
        assertEquals(agent, store.rows.single().builtByAgentId)
    }

    @Test
    fun `agent B is rejected with DuplicateBuildingAtNode when A has an in-progress same-type build at the node`() {
        val agentB = AgentId(UUID.randomUUID())
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())),
            positions = mapOf(agent to nodeId, agentB to nodeId),
            bodies = mapOf(
                agent to AgentBody(50, 50, 50, 50, 0, 0),
                agentB to AgentBody(50, 50, 50, 50, 0, 0),
            ),
            inventories = mapOf(
                agentB to AgentInventory().add(wood, 100).add(stone, 100),
            ),
        )
        val agentARow = sampleBuilding(progress = 2)
        val store = StubBuildingsStore(rows = mutableListOf(agentARow))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agentB, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 5,
        )

        val rejection = assertIs<WorldRejection.DuplicateBuildingAtNode>(result.leftOrNull())
        assertEquals(BuildingType.CAMPFIRE, rejection.type)
        assertEquals(nodeId, rejection.node)
        assertEquals(agentARow.instanceId, rejection.existingInstanceId)
        assertEquals(1, store.rows.size)
        assertEquals(2, store.rows.single().progressSteps)
    }

    @Test
    fun `rejects with DuplicateBuildingAtNode when an ACTIVE same-type instance already occupies the node`() {
        val state = stateWith()
        val finished = sampleBuilding(progress = 5, totalSteps = 5)
        val store = StubBuildingsStore(rows = mutableListOf(finished))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 12,
        )

        val rejection = assertIs<WorldRejection.DuplicateBuildingAtNode>(result.leftOrNull())
        assertEquals(BuildingType.CAMPFIRE, rejection.type)
        assertEquals(finished.instanceId, rejection.existingInstanceId)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `accepts the foundation step when only a different-type instance occupies the node`() {
        val state = stateWith()
        val shelter = sampleBuilding(type = BuildingType.SHELTER, progress = 2, totalSteps = 2, hp = 80)
        val store = StubBuildingsStore(rows = mutableListOf(shelter))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()

        val events = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 7,
            ).getOrNull(),
        ).events

        val firstStep = assertIs<EnvironmentEvent.BuildingProgressed>(events.single())
        assertEquals(1, firstStep.step)
        assertEquals(BuildingType.CAMPFIRE, firstStep.type)
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `rejects with BuildingSkillTooLow when the def gates on a level the agent has not reached`() {
        // Forward-compat path: v1 buildings ship with requiredSkillLevel=0 (no gate).
        // Future tiers will set >0; assert the reducer enforces the threshold via the
        // agent's snapshot.
        val gatedCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    "CAMPFIRE" to campfireDef.copy(
                        skillBars = mapOf("CARPENTRY" to BarProperties(level = 5, steps = 5, materialsPerStep = mapOf("WOOD" to 2, "STONE" to 1))),
                    ),
                ),
            ),
        )
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 2) }

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            gatedCatalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.BuildingSkillTooLow>(result.leftOrNull())
        assertEquals(carpentry, rejection.skill)
        assertEquals(5, rejection.required)
        assertEquals(2, rejection.current)
    }

    @Test
    fun `accepts a build when the agent meets the gated requiredSkillLevel`() {
        val gatedCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    "CAMPFIRE" to campfireDef.copy(
                        skillBars = mapOf("CARPENTRY" to BarProperties(level = 2, steps = 5, materialsPerStep = mapOf("WOOD" to 2, "STONE" to 1))),
                    ),
                ),
            ),
        )
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 2) }

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            gatedCatalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        assertNotNull(result.getOrNull())
    }

    @Test
    fun `eight calls through reduceBuild produce 7 BuildingProgressed plus 1 BuildingConstructed each tagged with its own commandId`() {
        val chestType = BuildingType.STORAGE_CHEST
        val totalSteps = 8
        val customCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    chestType.name to BuildingProperties(
                        staminaPerStep = 1,
                        hp = 40,
                        categoryHint = BuildingCategoryHint.STORAGE,
                        skillBars = mapOf(
                            "CARPENTRY" to BarProperties(
                                steps = totalSteps,
                                materialsPerStep = mapOf("WOOD" to 16 / totalSteps),
                            ),
                        ),
                        chestCapacityGrams = 50_000,
                    ),
                ),
            ),
        )
        var state = stateWith(inventory = mapOf(wood to 100))
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()

        val emitted = mutableListOf<Pair<WorldEvent, UUID>>()
        repeat(totalSteps) { i ->
            val command = EnvironmentCommand.BuildStructure(agent, chestType)
            val out = assertNotNull(
                reduceBuild(
                    state.environment, state.body, state.core, command, customCatalog, skills, store, barsStore, StubSafeNodes(),
                    NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
                    triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker,
                    visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(),
                    tick = (100 + i).toLong(),
                ).getOrNull(),
            )
            state = state.copy(environment = out.sliceDelta).applyEffects(out.effects)
            emitted += out.events.single() to command.commandId
        }

        val progressedEvents = emitted.dropLast(1)
        val terminal = emitted.last()

        assertEquals(totalSteps - 1, progressedEvents.size)
        progressedEvents.forEachIndexed { i, (event, cmdId) ->
            val progressed = assertIs<EnvironmentEvent.BuildingProgressed>(event)
            assertEquals(i + 1, progressed.step, "step index for emission $i")
            assertEquals(totalSteps, progressed.totalSteps)
            assertEquals(chestType, progressed.type)
            assertEquals(nodeId, progressed.at)
            assertEquals(agent, progressed.agent)
            assertEquals(cmdId, progressed.causedBy, "causedBy must match per-call commandId")
        }
        val constructed = assertIs<EnvironmentEvent.BuildingConstructed>(terminal.first)
        assertEquals(totalSteps, constructed.totalSteps)
        assertEquals(chestType, constructed.type)
        assertEquals(nodeId, constructed.at)
        assertEquals(agent, constructed.agent)
        assertEquals(terminal.second, constructed.causedBy)

        assertEquals(1, store.rows.size)
        assertEquals(BuildingStatus.ACTIVE, store.rows.single().status)
        assertEquals(totalSteps, store.rows.single().progressSteps)
    }

    @Test
    fun `walking the full step ladder accumulates per-step stamina cost and ends ACTIVE`() {
        var state = stateWith(inventory = mapOf(wood to 100, stone to 100))
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()
        val initialStamina = state.bodyOf(agent)!!.stamina
        var lastEvent: WorldEvent? = null

        repeat(5) { i ->
            val out = assertNotNull(
                reduceBuild(
                    state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                    catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = (10 + i).toLong(),
                ).getOrNull(),
            )
            state = state.copy(environment = out.sliceDelta).applyEffects(out.effects)
            lastEvent = out.events.single()
        }

        assertIs<EnvironmentEvent.BuildingConstructed>(lastEvent)
        assertEquals(initialStamina - 5 * 8, state.bodyOf(agent)!!.stamina)
        assertEquals(1, store.rows.size)
        assertEquals(BuildingStatus.ACTIVE, store.rows.single().status)
        assertEquals(5, store.rows.single().progressSteps)
        assertEquals(90, state.inventoryOf(agent).quantityOf(wood))
        assertEquals(95, state.inventoryOf(agent).quantityOf(stone))
    }

    @Test
    fun `slotted skill receives one XP per build step`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(carpentry) }

        reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        assertEquals(listOf(carpentry to 1), skills.xpAddCalls)
    }

    private val survival = SkillId("SURVIVAL")

    private val watchtowerCatalog = BuildingsCatalog(
        BuildingDefinitionProperties(
            catalog = mapOf(
                "WATCHTOWER" to BuildingProperties(
                    staminaPerStep = 8,
                    hp = 50,
                    categoryHint = BuildingCategoryHint.RESIDENCE,
                    skillBars = mapOf(
                        "CARPENTRY" to BarProperties(level = 15, steps = 8, materialsPerStep = mapOf("WOOD" to 1)),
                        "SURVIVAL" to BarProperties(level = 10, steps = 6, materialsPerStep = mapOf("STONE" to 1)),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `multi-bar single-specialist — build with CARPENTRY advances only the CARPENTRY bar`() {
        val state = stateWith()
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = watchtowerCatalog }
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 15); slot(survival, level = 10) }
        val command = EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = carpentry)

        val events = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, command, watchtowerCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
                SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
                behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
            ).getOrNull(),
        ).events

        val instanceId = store.rows.single().instanceId
        val bars = barsStore.barsByInstance(instanceId)
        val carpentryBar = bars.single { it.skill == carpentry }
        val survivalBar = bars.single { it.skill == survival }
        assertEquals(1, carpentryBar.progressSteps)
        assertEquals(0, survivalBar.progressSteps)
        assertEquals(1, store.rows.single().progressSteps)
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, store.rows.single().status)
        assertIs<EnvironmentEvent.BuildingProgressed>(events.single())
    }

    @Test
    fun `multi-bar two-specialist alternating — 14th call flips status to ACTIVE`() {
        val state = stateWith(stamina = 200, inventory = mapOf(wood to 200, stone to 200))
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = watchtowerCatalog }
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 15); slot(survival, level = 10) }
        var currentState = state
        var lastEvents: List<WorldEvent> = emptyList()

        repeat(8) { i ->
            val cmd = EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = carpentry)
            val out = assertNotNull(
                reduceBuild(
                    currentState.environment, currentState.body, currentState.core, cmd, watchtowerCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
                    SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
                    behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = (1 + i).toLong(),
                ).getOrNull(),
            )
            currentState = currentState.copy(environment = out.sliceDelta).applyEffects(out.effects)
            lastEvents = out.events
        }

        repeat(6) { i ->
            val cmd = EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = survival)
            val out = assertNotNull(
                reduceBuild(
                    currentState.environment, currentState.body, currentState.core, cmd, watchtowerCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
                    SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
                    behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = (9 + i).toLong(),
                ).getOrNull(),
            )
            currentState = currentState.copy(environment = out.sliceDelta).applyEffects(out.effects)
            lastEvents = out.events
        }

        assertIs<EnvironmentEvent.BuildingConstructed>(lastEvents.single())
        assertEquals(BuildingStatus.ACTIVE, store.rows.single().status)
    }

    @Test
    fun `build on multi-bar building without specifying skill rejects with SkillRequiredForMultiBar`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 15); slot(survival, level = 10) }

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = null),
            watchtowerCatalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
            SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.SkillRequiredForMultiBar>(result.leftOrNull())
        assertEquals(agent, rejection.agent)
        assertEquals(BuildingType.WATCHTOWER, rejection.type)
    }

    @Test
    fun `build naming a skill not in the building rejects with BarNotInBuilding`() {
        val state = stateWith()
        val alchemy = SkillId("ALCHEMY")
        val skills = StubSkillsRegistry().apply { slot(alchemy, level = 20) }

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = alchemy),
            watchtowerCatalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
            SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.BarNotInBuilding>(result.leftOrNull())
        assertEquals(agent, rejection.agent)
        assertEquals(BuildingType.WATCHTOWER, rejection.type)
        assertEquals(alchemy, rejection.skill)
    }

    @Test
    fun `build on a bar already at its total steps rejects with BarAlreadyComplete`() {
        val state = stateWith()
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = watchtowerCatalog }
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 15); slot(survival, level = 10) }

        val instanceId = UUID.randomUUID()
        val underConstruction = Building(
            instanceId = instanceId,
            nodeId = nodeId,
            type = BuildingType.WATCHTOWER,
            status = BuildingStatus.UNDER_CONSTRUCTION,
            builtByAgentId = agent,
            builtAtTick = 1L,
            lastProgressTick = 1L,
            progressSteps = 8,
            totalSteps = 14,
            hpCurrent = 50,
            hpMax = 50,
        )
        store.rows += underConstruction
        barsStore.rows[instanceId to "CARPENTRY"] = BuildingBar(instanceId, carpentry, progressSteps = 8, totalSteps = 8)
        barsStore.rows[instanceId to "SURVIVAL"] = BuildingBar(instanceId, survival, progressSteps = 0, totalSteps = 6)

        val result = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = carpentry),
            watchtowerCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
            SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 5,
        )

        val rejection = assertIs<WorldRejection.BarAlreadyComplete>(result.leftOrNull())
        assertEquals(agent, rejection.agent)
        assertEquals(BuildingType.WATCHTOWER, rejection.type)
        assertEquals(carpentry, rejection.skill)
    }

    @Test
    fun `per-bar skill-level gate is checked independently — low-level bar rejects, sufficient-level bar succeeds`() {
        val state = stateWith()
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = watchtowerCatalog }
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 15); slot(survival, level = 0) }

        val survivalResult = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = survival),
            watchtowerCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
            SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.BuildingSkillTooLow>(survivalResult.leftOrNull())
        assertEquals(survival, rejection.skill)
        assertEquals(10, rejection.required)
        assertEquals(0, rejection.current)

        val carpentryResult = reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = carpentry),
            watchtowerCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
            SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 2,
        )

        assertNotNull(carpentryResult.getOrNull())
    }

    @Test
    fun `XP accrues on the bar's own skill, not any other skill`() {
        val state = stateWith()
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = watchtowerCatalog }
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 15); slot(survival, level = 10) }

        reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WATCHTOWER, skill = survival),
            watchtowerCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
            SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 1,
        )

        assertEquals(listOf(survival to 1), skills.xpAddCalls)
    }

    @Test
    fun `unslotted skill triggers a SkillRecommended event when maybeRecommend says yes`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { recommendOnNext[carpentry] = 1 }
        val publisher = RecordingPublisher()

        reduceBuild(
            state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, NoGateStates, NoAgentKeys, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 5,
        )

        val rec = publisher.events.filterIsInstance<AgentEvent.SkillRecommended>().single()
        assertEquals(carpentry, rec.skill)
        assertEquals(1, rec.recommendCount)
    }

    private fun sampleBuilding(
        type: BuildingType = BuildingType.CAMPFIRE,
        progress: Int = 1,
        totalSteps: Int = 5,
        hp: Int = 30,
    ): Building = Building(
        instanceId = UUID.randomUUID(),
        nodeId = nodeId,
        type = type,
        status = if (progress == totalSteps) BuildingStatus.ACTIVE else BuildingStatus.UNDER_CONSTRUCTION,
        builtByAgentId = agent,
        builtAtTick = 1L,
        lastProgressTick = 1L,
        progressSteps = progress,
        totalSteps = totalSteps,
        hpCurrent = hp,
        hpMax = hp,
    )

    private inner class StubBuildingsStore(
        val rows: MutableList<Building> = mutableListOf(),
    ) : BuildingsStore {
        val inserted = mutableListOf<Building>()
        val advanced = mutableListOf<UUID>()
        val completed = mutableListOf<UUID>()

        override fun insert(building: Building) {
            inserted += building
            rows += building
        }
        override fun findById(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? =
            rows.firstOrNull {
                it.nodeId == node && it.builtByAgentId == agent && it.type == type &&
                    it.status == BuildingStatus.UNDER_CONSTRUCTION
            }
        override fun findAnyAtNodeOfType(node: NodeId, type: BuildingType): Building? =
            rows.firstOrNull { it.nodeId == node && it.type == type }
        override fun listAtNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }

        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? {
            val idx = rows.indexOfFirst { it.instanceId == id }.takeIf { it >= 0 } ?: return null
            advanced += id
            val updated = rows[idx].copy(progressSteps = newProgress, lastProgressTick = asOfTick)
            rows[idx] = updated
            return updated
        }

        override fun complete(id: UUID, asOfTick: Long): Building? {
            val idx = rows.indexOfFirst { it.instanceId == id }.takeIf { it >= 0 } ?: return null
            completed += id
            val original = rows[idx]
            val updated = original.copy(
                status = BuildingStatus.ACTIVE,
                progressSteps = original.totalSteps,
                lastProgressTick = asOfTick,
            )
            rows[idx] = updated
            return updated
        }
    }

    private inner class StubBuildingBarsStore : BuildingBarsStore {
        val rows: MutableMap<Pair<UUID, String>, BuildingBar> = mutableMapOf()
        var storeRef: StubBuildingsStore? = null
        var catalogRef: BuildingsCatalog? = null

        override fun insertAll(bars: List<BuildingBar>) {
            bars.forEach { rows[it.instanceId to it.skill.value] = it }
        }
        override fun barsByInstance(instanceId: UUID): List<BuildingBar> =
            rows.values.filter { it.instanceId == instanceId }.sortedBy { it.skill.value }
        override fun barsByInstances(instanceIds: Set<UUID>): Map<UUID, List<BuildingBar>> =
            rows.values.filter { it.instanceId in instanceIds }.groupBy { it.instanceId }
        override fun advanceBar(instanceId: UUID, skill: dev.gvart.genesara.player.SkillId): BuildingBar? {
            val key = instanceId to skill.value
            val existing = rows[key]
            if (existing != null) {
                if (existing.progressSteps >= existing.totalSteps) return null
                val updated = existing.copy(progressSteps = existing.progressSteps + 1)
                rows[key] = updated
                return updated
            }
            // For tests that pre-seed a building row without explicit bar rows, mirror what the
            // first build step would have inserted, then advance one step. Uses catalogRef (set
            // by the test) for the building def; falls back to the class-level catalog field.
            val resolvedCatalog = catalogRef ?: catalog
            val building = rowOf(instanceId) ?: return null
            val def = try { resolvedCatalog.def(building.type) } catch (_: Throwable) { return null }
            val barDef = def.bar(skill) ?: return null
            val advanced = (building.progressSteps).coerceAtMost(barDef.steps - 1) + 1
            val seeded = BuildingBar(instanceId, skill, progressSteps = advanced, totalSteps = barDef.steps)
            rows[key] = seeded
            return seeded
        }
        private fun rowOf(id: UUID): Building? = storeRef?.rows?.firstOrNull { it.instanceId == id }
    }

    private class StubSafeNodes : AgentSafeNodeGateway {
        val set = mutableMapOf<AgentId, NodeId>()
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {
            set[agentId] = nodeId
        }
        override fun find(agentId: AgentId): NodeId? = set[agentId]
        override fun clear(agentId: AgentId) {
            set.remove(agentId)
        }
    }

    private object NoOpAgentPlotsStore : dev.gvart.genesara.world.AgentPlotsStore {
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) = Unit
        override fun findById(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> = emptyMap()
        override fun plant(plotId: java.util.UUID, crop: dev.gvart.genesara.world.PlantedCrop): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: java.util.UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: java.util.UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> = emptyList()
    }

    private object NoGateStates : dev.gvart.genesara.world.BuildingGateStateStore {
        override fun insertClosed(gateInstanceId: java.util.UUID) = Unit
        override fun isOpen(gateInstanceId: java.util.UUID): Boolean? = null
        override fun toggle(gateInstanceId: java.util.UUID): Boolean? = null
    }

    private object NoAgentKeys : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: java.util.UUID): Boolean = false
    }

    private class RecordingGateStates : dev.gvart.genesara.world.BuildingGateStateStore {
        val insertedClosed = mutableListOf<java.util.UUID>()
        override fun insertClosed(gateInstanceId: java.util.UUID) { insertedClosed += gateInstanceId }
        override fun isOpen(gateInstanceId: java.util.UUID): Boolean? = false
        override fun toggle(gateInstanceId: java.util.UUID): Boolean? = true
    }

    private class RecordingAgentKeys : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: java.util.UUID): Boolean = false
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val slottedSkills = mutableSetOf<SkillId>()
        private val levels = mutableMapOf<SkillId, Int>()
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendOnNext = mutableMapOf<SkillId, Int?>()
        var slotCount: Int = 8
        var slotsFilled: Int = 0

        fun slot(skill: SkillId, level: Int = 0) {
            slottedSkills += skill
            levels[skill] = level
            slotsFilled = slottedSkills.size
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(
            perSkill = slottedSkills.associateWith { skillId ->
                AgentSkillState(
                    skill = skillId,
                    xp = 0,
                    level = levels[skillId] ?: 0,
                    slotIndex = slottedSkills.indexOf(skillId),
                    recommendCount = 0,
                )
            },
            slotCount = slotCount,
            slotsFilled = slotsFilled,
        )

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            if (skill !in slottedSkills) return AddXpResult.Unslotted
            xpAddCalls += skill to delta
            return AddXpResult.Accrued(emptyList())
        }

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? =
            if (skill in slottedSkills) null else recommendOnNext.remove(skill)

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    @Test
    fun `completing a WOODEN_WALL triggers vision-blocker cache recompute for the tile`() {
        val wallDef = BuildingProperties(
            staminaPerStep = 1, hp = 50, categoryHint = BuildingCategoryHint.DEFENSIVE,
            skillBars = mapOf("CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 1))),
            sightBlockerHeight = 1,
        )
        val wallCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(catalog = mapOf("WOODEN_WALL" to wallDef)),
        )
        val state = stateWith(inventory = mapOf(wood to 5))
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = wallCatalog }
        val skills = StubSkillsRegistry()
        val recomputed = mutableListOf<NodeId>()
        val cache = object : dev.gvart.genesara.world.internal.vision.VisionBlockerCache {
            override fun blockerHeights(nodes: Set<NodeId>): Map<NodeId, Int> = emptyMap()
            override fun recomputeForNode(nodeId: NodeId) { recomputed += nodeId }
            override fun seedAll() = Unit
            override fun flush() = Unit
        }

        // Step 1: progress; no completion yet.
        val step1Out = assertNotNull(
            reduceBuild(
                state.environment, state.body, state.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WOODEN_WALL),
                wallCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore,
                NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
                triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker,
                visionBlockers = cache, tick = 1,
            ).getOrNull(),
        )
        val afterStep1 = state.copy(environment = step1Out.sliceDelta).applyEffects(step1Out.effects)
        assertTrue(recomputed.isEmpty(), "no recompute should fire until the wall hits ACTIVE")

        // Step 2: completion → recompute fires.
        reduceBuild(
            afterStep1.environment, afterStep1.body, afterStep1.core, EnvironmentCommand.BuildStructure(agent, BuildingType.WOODEN_WALL),
            wallCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore,
            NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker,
            visionBlockers = cache, tick = 2,
        )
        assertEquals(listOf(nodeId), recomputed)
    }

    @Test
    fun `completing a non-sight-blocker (CAMPFIRE) does not call recomputeForNode`() {
        val state = stateWith(inventory = mapOf(wood to 100, stone to 100))
        val store = StubBuildingsStore()
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store; it.catalogRef = catalog }
        val skills = StubSkillsRegistry()
        val recomputed = mutableListOf<NodeId>()
        val cache = object : dev.gvart.genesara.world.internal.vision.VisionBlockerCache {
            override fun blockerHeights(nodes: Set<NodeId>): Map<NodeId, Int> = emptyMap()
            override fun recomputeForNode(nodeId: NodeId) { recomputed += nodeId }
            override fun seedAll() = Unit
            override fun flush() = Unit
        }

        var s = state
        // CAMPFIRE has 5 steps in the in-test catalog.
        for (i in 1..5) {
            val out = assertNotNull(
                reduceBuild(
                    s.environment, s.body, s.core, EnvironmentCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                    catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore,
                    NoGateStates, NoAgentKeys, SkillProgression(skills, RecordingPublisher()),
                    triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker,
                    visionBlockers = cache, tick = i.toLong(),
                ).getOrNull(),
            )
            s = s.copy(environment = out.sliceDelta).applyEffects(out.effects)
        }
        assertTrue(recomputed.isEmpty(), "CAMPFIRE has sightBlockerHeight=0 — no recompute should ever fire")
    }
}

private fun <L, R> arrow.core.Either<L, R>.leftOrNull(): L? = (this as? arrow.core.Either.Left<L>)?.value
