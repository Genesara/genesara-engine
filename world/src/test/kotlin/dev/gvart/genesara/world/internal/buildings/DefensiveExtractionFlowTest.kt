package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AgentKnownRecipesGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingBar
import dev.gvart.genesara.world.BuildingBarsStore
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.RecipeUnlockMode
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.crafting.RarityRoller
import dev.gvart.genesara.world.internal.crafting.reduceCraft
import dev.gvart.genesara.world.internal.extract.reduceExtract
import dev.gvart.genesara.world.internal.harvest.reduceHarvest
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.movement.reduceMove
import dev.gvart.genesara.world.internal.resources.InitialResourceRow
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefensiveExtractionFlowTest {

    private val agent = AgentId(UUID.randomUUID())
    private val ownerId = PlayerId(UUID.randomUUID())
    private val tracker = InMemoryBehaviorTracker()
    private val publisher = RecordingPublisher()

    private val regionId = RegionId(1L)
    private val mountainNode = NodeId(1L)
    private val gateNode = NodeId(2L)

    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val ironIngot = ItemId("IRON_INGOT")
    private val coal = ItemId("COAL")
    private val gateKey = ItemId("GATE_KEY")

    private val carpentry = SkillId("CARPENTRY")
    private val smithing = SkillId("SMITHING")

    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.MOUNTAIN, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private val mineDef = BuildingProperties(
        staminaPerStep = 8, hp = 80,
        categoryHint = BuildingCategoryHint.EXTRACTION_MINE,
        skillBars = mapOf("CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 1))),
    )
    private val gateDef = BuildingProperties(
        staminaPerStep = 8, hp = 120,
        categoryHint = BuildingCategoryHint.DEFENSIVE,
        skillBars = mapOf(
            "CARPENTRY" to BarProperties(steps = 2, materialsPerStep = mapOf("WOOD" to 1)),
            "SMITHING" to BarProperties(steps = 2, materialsPerStep = mapOf("IRON_INGOT" to 1)),
        ),
    )
    private val catalog = BuildingsCatalog(
        BuildingDefinitionProperties(catalog = mapOf("MINE" to mineDef, "GATE" to gateDef)),
    )

    private val gateKeyCopyRecipe = Recipe(
        id = RecipeId("GATE_KEY_COPY"),
        output = RecipeOutput(item = gateKey, quantity = 1),
        inputs = mapOf(ironIngot to 1),
        requiredStation = BuildingCategoryHint.CRAFTING_STATION_WOOD,
        requiredSkill = carpentry,
        requiredSkillLevel = 0,
        staminaCost = 10,
        unlockMode = RecipeUnlockMode.Open,
        requiresSource = gateKey,
    )

    private val itemLookup = FlowItemLookup(
        mapOf(
            wood to resource(wood),
            stone to resource(stone),
            ironIngot to resource(ironIngot),
            coal to resource(coal, extractionOnly = true),
            gateKey to key(gateKey),
        ),
    )

    private val balance = FlowBalance(harvestCost = 5)

    @Test
    fun `mine-gate-extract-block-toggle-move-keycopy-harvest-block flow`() {
        val buildStore = FlowBuildingsStore()
        val barsStore = FlowBuildingBarsStore(buildStore, catalog)
        val gateStates = FlowGateStates()
        val agentKeys = FlowAgentKeysStore()
        val skills = FlowSkillsRegistry().apply { slot(carpentry); slot(smithing) }
        val safeNodes = FlowSafeNodes()
        val resources = FlowResourceStore(mapOf(coal to 50))
        val agentRegistry = FlowAgentRegistry(agent, ownerId)
        val equipment = FlowEquipmentStore()
        val recipeLookup = FlowRecipeLookup(listOf(gateKeyCopyRecipe))

        fun buildingsLookupFromStore(): BuildingsLookup = FlowBuildingsLookup(buildStore)

        var state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(
                mountainNode to Node(mountainNode, regionId, q = 0, r = 0, terrain = Terrain.MOUNTAIN, adjacency = setOf(gateNode)),
                gateNode to Node(gateNode, regionId, q = 1, r = 0, terrain = Terrain.MOUNTAIN, adjacency = setOf(mountainNode)),
            ),
            positions = mapOf(agent to mountainNode),
            bodies = mapOf(agent to AgentBody(hp = 100, maxHp = 100, stamina = 500, maxStamina = 500, mana = 0, maxMana = 0)),
            inventories = mapOf(agent to AgentInventory()
                .add(wood, 50).add(stone, 50).add(ironIngot, 20)),
        )

        // --- Step 1: Build MINE (2 steps) ---
        repeat(2) { i ->
            val (next, events) = assertNotNull(
                reduceBuild(
                    state, WorldCommand.BuildStructure(agent, BuildingType.MINE, skill = carpentry),
                    catalog, skills, buildStore, barsStore, safeNodes,
                    NoOpAgentPlotsStore, NoGateStates, NoAgentKeys,
                    SkillProgression(skills, publisher),
                    triggeredPassives = NoOpTriggeredPassiveDispatcher,
                    behaviorTracker = tracker,
                    visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(),
                    tick = (1 + i).toLong(),
                ).getOrNull(),
                "Step 1.${i + 1}: MINE build step ${i + 1} failed",
            )
            state = next
            if (i == 1) assertIs<WorldEvent.BuildingConstructed>(events.single(), "Step 1: final build step must emit BuildingConstructed")
        }
        val mineInstance = buildStore.rows.single { it.type == BuildingType.MINE }
        assertEquals(BuildingStatus.ACTIVE, mineInstance.status, "Step 1: MINE must be ACTIVE")
        assertEquals(Terrain.MOUNTAIN, state.nodes[mountainNode]!!.terrain, "Step 1: terrain-coupling passed — MOUNTAIN is in the allowed set")

        // --- Step 2: Extract COAL ---
        val (afterExtract, extractEvents) = assertNotNull(
            reduceExtract(
                state,
                WorldCommand.Extract(agent, coal),
                balance,
                itemLookup,
                resources,
                buildingsLookupFromStore(),
                agentRegistry,
                equipment,
                SkillProgression(skills, publisher),
                characterXp = CharacterXpProgression.NoOp,
                scaling = NoScaling,
                triggeredPassives = NoOpTriggeredPassiveDispatcher,
                behaviorTracker = tracker,
                tick = 10,
            ).getOrNull(),
            "Step 2: Extract COAL must succeed",
        )
        state = afterExtract
        val extracted = assertIs<WorldEvent.ResourceExtracted>(extractEvents.single(), "Step 2: must emit ResourceExtracted")
        assertEquals(coal, extracted.item, "Step 2: extracted item must be COAL")
        assertTrue(state.inventoryOf(agent).quantityOf(coal) > 0, "Step 2: COAL must be in inventory")

        // --- Step 3: Build GATE on mountainNode (same node as MINE, 4 steps: 2 carpentry + 2 smithing) ---
        // Agent stays on mountainNode so the toggle (step 5) can be issued from the gate's own node.
        // The movement block test (step 4) uses a separate agent position copy.

        repeat(2) { i ->
            val (next, _) = assertNotNull(
                reduceBuild(
                    state, WorldCommand.BuildStructure(agent, BuildingType.GATE, skill = carpentry),
                    catalog, skills, buildStore, barsStore, safeNodes,
                    NoOpAgentPlotsStore, gateStates, agentKeys,
                    SkillProgression(skills, publisher),
                    triggeredPassives = NoOpTriggeredPassiveDispatcher,
                    behaviorTracker = tracker,
                    visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(),
                    tick = (20 + i).toLong(),
                ).getOrNull(),
                "Step 3: GATE carpentry step ${i + 1} failed",
            )
            state = next
        }
        var gateCompletionEvents: List<WorldEvent> = emptyList()
        repeat(2) { i ->
            val (next, events) = assertNotNull(
                reduceBuild(
                    state, WorldCommand.BuildStructure(agent, BuildingType.GATE, skill = smithing),
                    catalog, skills, buildStore, barsStore, safeNodes,
                    NoOpAgentPlotsStore, gateStates, agentKeys,
                    SkillProgression(skills, publisher),
                    triggeredPassives = NoOpTriggeredPassiveDispatcher,
                    behaviorTracker = tracker,
                    visionBlockers = dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(),
                    tick = (22 + i).toLong(),
                ).getOrNull(),
                "Step 3: GATE smithing step ${i + 1} failed",
            )
            state = next
            if (i == 1) { gateCompletionEvents = events }
        }
        assertIs<WorldEvent.BuildingConstructed>(gateCompletionEvents.first(), "Step 3: final GATE step must emit BuildingConstructed")
        val gateInstance = buildStore.rows.single { it.type == BuildingType.GATE }
        assertEquals(BuildingStatus.ACTIVE, gateInstance.status, "Step 3: GATE must be ACTIVE")
        assertEquals(listOf(gateInstance.instanceId), gateStates.closedInserted, "Step 3: gate state row must be inserted as CLOSED")
        val issuedKey = agentKeys.insertedKeys.single()
        assertEquals(gateInstance.instanceId, issuedKey.gateInstanceId, "Step 3: auto-issued key must reference the gate")
        assertEquals(agent, issuedKey.agentId, "Step 3: key must be issued to the builder")
        val mintedEvent = assertIs<WorldEvent.GateKeyMinted>(
            gateCompletionEvents.filterIsInstance<WorldEvent.GateKeyMinted>().single(),
            "Step 3: GateKeyMinted must be in completion events",
        )
        assertEquals(false, mintedEvent.byCopy, "Step 3: GateKeyMinted must have byCopy=false")
        assertEquals(gateInstance.instanceId, mintedEvent.gateId, "Step 3: GateKeyMinted must reference the gate")

        // --- Step 4: Attempt to move from gateNode into mountainNode while gate is CLOSED ---
        // Simulate an outsider on gateNode trying to enter the fortified mountainNode.
        val outsiderState = state.copy(core = state.core.copy(positions = mapOf(agent to gateNode)))
        val moveBlockResult = reduceMove(
            outsiderState,
            WorldCommand.MoveAgent(agent, mountainNode),
            balance,
            buildingsLookupFromStore(),
            gateStates = gateStates,
            scaling = NoScaling,
            behaviorTracker = tracker,
            tick = 30,
        )
        val blockRejection = assertIs<WorldRejection.DefensiveBlocks>(
            moveBlockResult.leftOrNull(),
            "Step 4: closed GATE must block movement into mountainNode",
        )
        assertEquals(agent, blockRejection.agent, "Step 4: rejection must name our agent")
        assertEquals(mountainNode, blockRejection.node, "Step 4: rejection must name the destination node")

        // --- Step 5: Toggle the gate open (agent is already on mountainNode — the gate's node) ---
        val (afterToggle, toggleEvents) = assertNotNull(
            reduceToggleGate(
                state,
                WorldCommand.ToggleGate(agent, gateInstance.instanceId),
                buildStore,
                gateStates,
                agentKeys,
                dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(),
                tick = 31,
            ).getOrNull(),
            "Step 5: ToggleGate must succeed with the auto-issued key",
        )
        state = afterToggle
        val toggled = assertIs<WorldEvent.GateToggled>(toggleEvents.single(), "Step 5: must emit GateToggled")
        assertTrue(toggled.isOpen, "Step 5: gate must be OPEN after toggle")

        // --- Step 6: Move through the now-open gate (outsider can now enter) ---
        val (afterMove, moveEvents) = assertNotNull(
            reduceMove(
                outsiderState,
                WorldCommand.MoveAgent(agent, mountainNode),
                balance,
                buildingsLookupFromStore(),
                gateStates = gateStates,
                scaling = NoScaling,
                behaviorTracker = tracker,
                tick = 32,
            ).getOrNull(),
            "Step 6: movement through open GATE must succeed",
        )
        state = afterMove
        assertIs<WorldEvent.AgentMoved>(moveEvents.single(), "Step 6: must emit AgentMoved")
        assertEquals(mountainNode, state.positions[agent], "Step 6: agent must be on mountainNode after move")

        // --- Step 7: Craft GATE_KEY_COPY at WORKBENCH (on mountainNode) ---
        state = state.copy(core = state.core.copy(positions = mapOf(agent to mountainNode)))
        val workbenchBuilding = Building(
            instanceId = UUID.randomUUID(),
            nodeId = mountainNode,
            type = BuildingType.WORKBENCH,
            status = BuildingStatus.ACTIVE,
            builtByAgentId = agent,
            builtAtTick = 1L, lastProgressTick = 1L,
            progressSteps = 1, totalSteps = 1,
            hpCurrent = 50, hpMax = 50,
        )
        buildStore.rows += workbenchBuilding

        val ironIngotBefore = state.inventoryOf(agent).quantityOf(ironIngot)
        val (afterCraft, craftEvents) = assertNotNull(
            reduceCraft(
                state,
                WorldCommand.CraftItem(agent, gateKeyCopyRecipe.id, source = issuedKey.instanceId),
                balance,
                itemLookup,
                recipeLookup,
                AgentKnownRecipesGateway.Empty,
                agentKeys,
                buildingsLookupFromStore(),
                skills,
                agentRegistry,
                fixedRoller(),
                SkillProgression(skills, publisher),
                scaling = NoScaling,
                triggeredPassives = NoOpTriggeredPassiveDispatcher,
                behaviorTracker = tracker,
                tick = 40,
            ).getOrNull(),
            "Step 7: GATE_KEY_COPY craft must succeed",
        )
        state = afterCraft
        assertIs<WorldEvent.ItemCrafted>(craftEvents.filterIsInstance<WorldEvent.ItemCrafted>().single(), "Step 7: must emit ItemCrafted")
        val copyMinted = assertIs<WorldEvent.GateKeyMinted>(
            craftEvents.filterIsInstance<WorldEvent.GateKeyMinted>().single(),
            "Step 7: must emit GateKeyMinted",
        )
        assertTrue(copyMinted.byCopy, "Step 7: GateKeyMinted byCopy must be true")
        assertEquals(gateInstance.instanceId, copyMinted.gateId, "Step 7: copied key must reference same gate")
        val copiedKey = agentKeys.insertedKeys.last()
        assertEquals(gateInstance.instanceId, copiedKey.gateInstanceId, "Step 7: copied key in store must point at original gate")
        assertEquals(ironIngotBefore - 1, state.inventoryOf(agent).quantityOf(ironIngot), "Step 7: IRON_INGOT must be consumed")

        // --- Step 8: Bare harvest of COAL is rejected (extractionOnly guard) ---
        val harvestResult = reduceHarvest(
            state,
            WorldCommand.Harvest(agent, coal),
            balance,
            itemLookup,
            resources,
            agentRegistry,
            equipment,
            SkillProgression(skills, publisher),
            characterXp = CharacterXpProgression.NoOp,
            scaling = NoScaling,
            triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker,
            tick = 50,
        )
        val harvestRejection = assertIs<WorldRejection.HarvestRequiresExtraction>(
            harvestResult.leftOrNull(),
            "Step 8: bare Harvest on extractionOnly COAL must be rejected",
        )
        assertEquals(coal, harvestRejection.item, "Step 8: rejection must name COAL")
    }

    // ---- stubs ----

    private fun resource(id: ItemId, extractionOnly: Boolean = false) = Item(
        id = id, displayName = id.value, description = "",
        category = ItemCategory.RESOURCE, weightPerUnit = 100, maxStack = 500,
        harvestSkill = null, extractionOnly = extractionOnly,
    )

    private fun key(id: ItemId) = Item(
        id = id, displayName = id.value, description = "",
        category = ItemCategory.KEY, weightPerUnit = 100, maxStack = 1,
        harvestSkill = null, extractionOnly = false,
    )

    private fun fixedRoller() = object : RarityRoller(Random(0)) {
        override fun roll(skillLevel: Int, luck: Int) = Rarity.COMMON
    }

    private object NoOpAgentPlotsStore : dev.gvart.genesara.world.AgentPlotsStore {
        override fun insertEmpty(plot: dev.gvart.genesara.world.AgentPlot) = Unit
        override fun findById(plotId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun findByBuilding(buildingInstanceId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.AgentPlot>> = emptyMap()
        override fun plant(plotId: UUID, crop: dev.gvart.genesara.world.PlantedCrop): dev.gvart.genesara.world.AgentPlot? = null
        override fun tend(plotId: UUID, tick: Long): dev.gvart.genesara.world.AgentPlot? = null
        override fun clearPlanting(plotId: UUID): dev.gvart.genesara.world.AgentPlot? = null
        override fun listPlantedSnapshot(): List<dev.gvart.genesara.world.AgentPlot> = emptyList()
    }

    private object NoGateStates : BuildingGateStateStore {
        override fun insertClosed(gateInstanceId: UUID) = Unit
        override fun isOpen(gateInstanceId: UUID): Boolean? = null
        override fun toggle(gateInstanceId: UUID): Boolean? = null
    }

    private object NoAgentKeys : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean = false
    }

    private class FlowBuildingsStore(val rows: MutableList<Building> = mutableListOf()) : BuildingsStore {
        override fun insert(building: Building) { rows += building }
        override fun findById(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? =
            rows.firstOrNull { it.nodeId == node && it.builtByAgentId == agent && it.type == type && it.status == BuildingStatus.UNDER_CONSTRUCTION }
        override fun findAnyAtNodeOfType(node: NodeId, type: BuildingType): Building? =
            rows.firstOrNull { it.nodeId == node && it.type == type }
        override fun listAtNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? {
            val idx = rows.indexOfFirst { it.instanceId == id }.takeIf { it >= 0 } ?: return null
            val updated = rows[idx].copy(progressSteps = newProgress, lastProgressTick = asOfTick)
            rows[idx] = updated
            return updated
        }
        override fun complete(id: UUID, asOfTick: Long): Building? {
            val idx = rows.indexOfFirst { it.instanceId == id }.takeIf { it >= 0 } ?: return null
            val original = rows[idx]
            val updated = original.copy(status = BuildingStatus.ACTIVE, progressSteps = original.totalSteps, lastProgressTick = asOfTick)
            rows[idx] = updated
            return updated
        }
    }

    private class FlowBuildingBarsStore(
        private val store: FlowBuildingsStore,
        private val catalog: BuildingsCatalog,
    ) : BuildingBarsStore {
        val rows: MutableMap<Pair<UUID, String>, BuildingBar> = mutableMapOf()

        override fun insertAll(bars: List<BuildingBar>) { bars.forEach { rows[it.instanceId to it.skill.value] = it } }
        override fun barsByInstance(instanceId: UUID): List<BuildingBar> =
            rows.values.filter { it.instanceId == instanceId }.sortedBy { it.skill.value }
        override fun barsByInstances(instanceIds: Set<UUID>): Map<UUID, List<BuildingBar>> =
            rows.values.filter { it.instanceId in instanceIds }.groupBy { it.instanceId }
        override fun advanceBar(instanceId: UUID, skill: SkillId): BuildingBar? {
            val key = instanceId to skill.value
            val existing = rows[key]
            if (existing != null) {
                if (existing.progressSteps >= existing.totalSteps) return null
                val updated = existing.copy(progressSteps = existing.progressSteps + 1)
                rows[key] = updated
                return updated
            }
            val building = store.rows.firstOrNull { it.instanceId == instanceId } ?: return null
            val def = try { catalog.def(building.type) } catch (_: Throwable) { return null }
            val barDef = def.bar(skill) ?: return null
            val advanced = (building.progressSteps).coerceAtMost(barDef.steps - 1) + 1
            val seeded = BuildingBar(instanceId, skill, progressSteps = advanced, totalSteps = barDef.steps)
            rows[key] = seeded
            return seeded
        }
    }

    private class FlowGateStates : BuildingGateStateStore {
        val closedInserted = mutableListOf<UUID>()
        private val states = mutableMapOf<UUID, Boolean>()
        override fun insertClosed(gateInstanceId: UUID) {
            closedInserted += gateInstanceId
            states[gateInstanceId] = false
        }
        override fun isOpen(gateInstanceId: UUID): Boolean? = states[gateInstanceId]
        override fun toggle(gateInstanceId: UUID): Boolean? {
            val current = states[gateInstanceId] ?: return null
            val next = !current
            states[gateInstanceId] = next
            return next
        }
    }

    private class FlowAgentKeysStore : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore()

    private class FlowSkillsRegistry : AgentSkillsRegistry {
        private val slotted = mutableSetOf<SkillId>()
        fun slot(skill: SkillId) { slotted += skill }
        override fun snapshot(agent: AgentId) = AgentSkillsSnapshot(
            perSkill = slotted.associateWith { AgentSkillState(it, xp = 0, level = 0, slotIndex = 0, recommendCount = 0) },
            slotCount = 8, slotsFilled = slotted.size,
        )
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class FlowSafeNodes : dev.gvart.genesara.world.AgentSafeNodeGateway {
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) = Unit
        override fun find(agentId: AgentId): NodeId? = null
        override fun clear(agentId: AgentId) = Unit
    }

    private class FlowResourceStore(initial: Map<ItemId, Int>) : NodeResourceStore {
        private val cells = initial.toMutableMap()
        override fun availability(nodeId: NodeId, item: ItemId, tick: Long): NodeResourceCell? =
            cells[item]?.let { NodeResourceCell(nodeId, item, it, it.coerceAtLeast(1)) }
        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {
            val have = cells[item] ?: error("no cell for $item")
            cells[item] = have - amount
        }
        override fun read(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources(
            cells.mapValues { (k, v) -> dev.gvart.genesara.world.NodeResourceView(k, v, v.coerceAtLeast(1)) },
        )
        override fun seed(rows: Collection<InitialResourceRow>, tick: Long) = Unit
    }

    private class FlowAgentRegistry(private val id: AgentId, private val owner: PlayerId) : AgentRegistry {
        override fun find(agentId: AgentId): Agent? = if (agentId == id) Agent(
            id = id, owner = owner, name = "test",
            attributes = AgentAttributes(strength = 100, dexterity = 10, constitution = 10, perception = 10, intelligence = 10, luck = 5),
        ) else null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class FlowEquipmentStore : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private class FlowRecipeLookup(recipes: List<Recipe>) : RecipeLookup {
        private val byId = recipes.associateBy { it.id }
        override fun byId(id: RecipeId): Recipe? = byId[id]
        override fun all(): List<Recipe> = byId.values.toList()
    }

    private class FlowItemLookup(private val items: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = items[id]
        override fun all(): List<Item> = items.values.toList()
    }

    private class FlowBuildingsLookup(private val store: FlowBuildingsStore) : BuildingsLookup {
        override fun byId(id: UUID): Building? = store.findById(id)
        override fun byNode(node: NodeId): List<Building> = store.listAtNode(node)
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = store.listByNodes(nodes)
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> =
            store.listAtNode(node).filter { it.status == BuildingStatus.ACTIVE && hintOf(it.type) == hint }
        private fun hintOf(type: BuildingType): BuildingCategoryHint? = when (type) {
            BuildingType.MINE -> BuildingCategoryHint.EXTRACTION_MINE
            BuildingType.GATE -> BuildingCategoryHint.DEFENSIVE
            BuildingType.WOODEN_WALL -> BuildingCategoryHint.DEFENSIVE
            BuildingType.WORKBENCH -> BuildingCategoryHint.CRAFTING_STATION_WOOD
            BuildingType.FORGE -> BuildingCategoryHint.CRAFTING_STATION_METAL
            else -> null
        }
    }

    private class FlowBalance(private val harvestCost: Int) : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = harvestCost
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun roadStaminaMultiplier(): Double = 0.5
        override fun carryGramsPerStrengthPoint(): Int = 1_000_000
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) { events += event }
    }
}

private fun <L, R> arrow.core.Either<L, R>.leftOrNull(): L? = (this as? arrow.core.Either.Left<L>)?.value
