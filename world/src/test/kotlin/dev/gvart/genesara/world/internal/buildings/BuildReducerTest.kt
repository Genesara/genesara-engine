package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
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
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val command = WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE)

        val (next, events) = assertNotNull(
            reduceBuild(
                state, command,
                catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 7,
            ).getOrNull(),
        )

        val progressed = assertIs<WorldEvent.BuildingProgressed>(events.single())
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
        val command = WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE)

        val (next, events) = assertNotNull(
            reduceBuild(
                state, command,
                catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 9,
            ).getOrNull(),
        )

        val progressed = assertIs<WorldEvent.BuildingProgressed>(events.single())
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
        val command = WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE)

        val (_, events) = assertNotNull(
            reduceBuild(
                state, command,
                catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 11,
            ).getOrNull(),
        )

        val completed = assertIs<WorldEvent.BuildingConstructed>(events.single())
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
    fun `final step charges the leftover material remainder, not floor(total over steps)`() {
        val customCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    "CAMPFIRE" to campfireDef.copy(
                        skillBars = mapOf("CARPENTRY" to BarProperties(steps = 3, materialsPerStep = mapOf("WOOD" to 3))),
                    ),
                ),
            ),
        )
        val state = stateWith(inventory = mapOf(wood to 10))
        val nearlyDone = sampleBuilding(progress = 2, totalSteps = 3)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val skills = StubSkillsRegistry()

        val (next, _) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                customCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        assertEquals(6, next.inventoryOf(agent).quantityOf(wood))
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
            state, WorldCommand.BuildStructure(agent, BuildingType.SHELTER),
            catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 11,
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, safeNodes, NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 11,
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
        val barsStore = StubBuildingBarsStore().also { it.storeRef = store }
        val plots = RecordingAgentPlotsStore()
        val skills = StubSkillsRegistry()

        reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.FARM_PLOT),
            plotCatalog, skills, store, barsStore, StubSafeNodes(), plots, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 11,
        )

        val inserted = plots.inserted.single()
        assertEquals(nearlyDone.instanceId, inserted.buildingInstanceId)
        assertEquals(nodeId, inserted.nodeId)
        assertNull(inserted.plant)
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), plots, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 11,
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
        )

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when stamina is below the per-step cost`() {
        val state = stateWith(stamina = 3)
        val skills = StubSkillsRegistry()
        val result = reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
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

        val (afterA, eventsA) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.STORAGE_CHEST),
                chestCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()),
                triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 100,
            ).getOrNull(),
        )
        assertIs<WorldEvent.BuildingProgressed>(eventsA.single())

        val rejection = reduceBuild(
            afterA, WorldCommand.BuildStructure(agentB, BuildingType.STORAGE_CHEST),
            chestCatalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()),
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 100,
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
            state, WorldCommand.BuildStructure(agentB, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 5,
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 12,
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

        val (_, events) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 7,
            ).getOrNull(),
        )

        val firstStep = assertIs<WorldEvent.BuildingProgressed>(events.single())
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            gatedCatalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            gatedCatalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
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
            val command = WorldCommand.BuildStructure(agent, chestType)
            val (next, events) = assertNotNull(
                reduceBuild(
                    state, command, customCatalog, skills, store, barsStore, StubSafeNodes(),
                    NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()),
                    triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker,
                    tick = (100 + i).toLong(),
                ).getOrNull(),
            )
            state = next
            emitted += events.single() to command.commandId
        }

        val progressedEvents = emitted.dropLast(1)
        val terminal = emitted.last()

        assertEquals(totalSteps - 1, progressedEvents.size)
        progressedEvents.forEachIndexed { i, (event, cmdId) ->
            val progressed = assertIs<WorldEvent.BuildingProgressed>(event)
            assertEquals(i + 1, progressed.step, "step index for emission $i")
            assertEquals(totalSteps, progressed.totalSteps)
            assertEquals(chestType, progressed.type)
            assertEquals(nodeId, progressed.at)
            assertEquals(agent, progressed.agent)
            assertEquals(cmdId, progressed.causedBy, "causedBy must match per-call commandId")
        }
        val constructed = assertIs<WorldEvent.BuildingConstructed>(terminal.first)
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
            val (next, events) = assertNotNull(
                reduceBuild(
                    state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                    catalog, skills, store, barsStore, StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = (10 + i).toLong(),
                ).getOrNull(),
            )
            state = next
            lastEvent = events.single()
        }

        assertIs<WorldEvent.BuildingConstructed>(lastEvent)
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
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, RecordingPublisher()), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
        )

        assertEquals(listOf(carpentry to 1), skills.xpAddCalls)
    }

    @Test
    fun `unslotted skill triggers a SkillRecommended event when maybeRecommend says yes`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { recommendOnNext[carpentry] = 1 }
        val publisher = RecordingPublisher()

        reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubBuildingBarsStore(), StubSafeNodes(), NoOpAgentPlotsStore, SkillProgression(skills, publisher), triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 5,
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
            // Auto-seed when the test forgot to pre-populate the side table for a pre-existing
            // building — mirrors the bars row a real reducer would have inserted on step 1.
            val def = try { catalog.def(rowOf(instanceId)!!.type) } catch (_: Throwable) { null }
            val barDef = def?.bar(skill) ?: return null
            val seeded = BuildingBar(instanceId, skill, progressSteps = barDef.steps, totalSteps = barDef.steps)
                .copy(progressSteps = (rowOf(instanceId)?.progressSteps ?: 0).coerceAtMost(barDef.steps - 1) + 1)
            rows[key] = seeded
            return seeded
        }
        private fun rowOf(id: UUID): Building? = storeRef?.rows?.firstOrNull { it.instanceId == id }
        var storeRef: StubBuildingsStore? = null
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
}

private fun <L, R> arrow.core.Either<L, R>.leftOrNull(): L? = (this as? arrow.core.Either.Left<L>)?.value
