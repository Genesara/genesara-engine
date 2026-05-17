package dev.gvart.genesara.world.internal.cultivation

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.AgentPlot
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Crop
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.PlantedCrop
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
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CultivationReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val otherNodeId = NodeId(2L)
    private val plotId = UUID.randomUUID()
    private val buildingId = UUID.randomUUID()
    private val wheat = CropId("WHEAT")
    private val farming = SkillId("FARMING")
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

    private val seedItem = ItemId("WHEAT_SEED")
    private val outputItem = ItemId("WHEAT")

    private val wheatCrop = Crop(
        id = wheat,
        seedItem = seedItem,
        ticksToRipe = 60,
        outputItem = outputItem,
        baseYield = 4,
        neglectWindowTicks = 30,
        requiredTerrain = setOf(Terrain.PLAINS),
        requiredFarmingLevel = 0,
        gainPerLevel = 0.05,
        maxLuckBonus = 0,
        staminaCostPlant = 6,
        staminaCostTend = 4,
        staminaCostHarvest = 8,
        farmingSkill = farming,
    )

    private val items = StubItemLookup(
        mapOf(
            seedItem to itemFor(seedItem),
            outputItem to itemFor(outputItem),
        ),
    )

    private val balance = stubBalance()

    private fun emptyPlot(at: NodeId = nodeId): AgentPlot =
        AgentPlot(
            plotId = plotId,
            buildingInstanceId = buildingId,
            nodeId = at,
            plant = null,
        )

    private fun stateWith(
        terrain: Terrain = Terrain.PLAINS,
        positioned: Boolean = true,
        positionedAt: NodeId = nodeId,
        stamina: Int = 30,
        seedCount: Int = 3,
    ): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(
            nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = terrain, adjacency = emptySet()),
            otherNodeId to Node(otherNodeId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
        ),
        positions = if (positioned) mapOf(agent to positionedAt) else emptyMap(),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = stamina, maxStamina = 50, mana = 0, maxMana = 0)),
        inventories = if (seedCount > 0) {
            mapOf(agent to AgentInventory.EMPTY.add(seedItem, seedCount))
        } else {
            emptyMap()
        },
    )

    // ─────────────────────────── plant ───────────────────────────

    @Test
    fun `plant happy path fills the plot, deducts a seed, spends stamina, emits CropPlanted`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }
        val crops = StubCropLookup(wheatCrop)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            crops, plots, agentsRegistry(), skills, SkillProgression(skills, publisher),
            tracker, tick = 100,
        )

        val (next, _, events) = assertNotNull(result.getOrNull())
        val event = assertIs<WorldEvent.CropPlanted>(events.single())
        assertEquals(wheat, event.crop)
        assertEquals(100L, event.plantedAtTick)
        assertEquals(160L, event.ripeAtTick)
        assertEquals(plotId, event.plotId)
        assertEquals(2, next.inventoryOf(agent).quantityOf(seedItem))
        assertEquals(24, next.bodyOf(agent)!!.stamina)
        val planted = assertNotNull(plots.findById(plotId)?.plant)
        assertEquals(wheat, planted.cropId)
        assertEquals(100L, planted.plantedAtTick)
        assertEquals(mapOf(ActionCategory.GATHER to 1), tracker.snapshotFor(agent))
    }

    @Test
    fun `plant rejects when the agent is not on the plot's node`() {
        val state = stateWith(positionedAt = otherNodeId)
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            StubSkillsRegistry(), SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            tracker, tick = 1,
        )

        val rej = assertIs<WorldRejection.NotOnPlotNode>(result.leftOrNull())
        assertEquals(otherNodeId, rej.agentAt)
        assertEquals(nodeId, rej.plotAt)
    }

    @Test
    fun `plant succeeds even when another agent built the plot — plots are unowned`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            StubSkillsRegistry(), SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            tracker, tick = 1,
        )

        assertNotNull(result.getOrNull())
        assertEquals(agent, plots.findById(plotId)?.plant?.plantedByAgentId)
    }

    @Test
    fun `plant rejects when the plot is already planted`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot())
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 1, lastTendedAtTick = 1, plantedByAgentId = agent))
        }

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            StubSkillsRegistry(), SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            tracker, tick = 1,
        )

        assertEquals(WorldRejection.PlotNotEmpty(agent, plotId, wheat), result.leftOrNull())
    }

    @Test
    fun `plant rejects when terrain is not in required-terrain`() {
        val state = stateWith(terrain = Terrain.SWAMP)
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            StubSkillsRegistry(), SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            tracker, tick = 1,
        )

        val rej = assertIs<WorldRejection.CropTerrainMismatch>(result.leftOrNull())
        assertEquals(Terrain.SWAMP, rej.terrain)
        assertEquals(setOf(Terrain.PLAINS), rej.allowed)
    }

    @Test
    fun `plant rejects when farming level is below the crop gate`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }
        val gatedCrop = wheatCrop.copy(requiredFarmingLevel = 50)

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(gatedCrop), plots, agentsRegistry(),
            StubSkillsRegistry(), SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            tracker, tick = 1,
        )

        assertEquals(
            WorldRejection.CropFarmingLevelTooLow(agent, wheat, required = 50, current = 0),
            result.leftOrNull(),
        )
    }

    @Test
    fun `plant rejects when the agent has no seed`() {
        val state = stateWith(seedCount = 0)
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            StubSkillsRegistry(), SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            tracker, tick = 1,
        )

        assertEquals(WorldRejection.MissingSeed(agent, wheat, seedItem), result.leftOrNull())
    }

    @Test
    fun `plant rejects when stamina is below the cost`() {
        val state = stateWith(stamina = 3)
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }

        val result = reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            StubSkillsRegistry(), SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            tracker, tick = 1,
        )

        assertEquals(
            WorldRejection.NotEnoughStamina(agent, required = 6, available = 3),
            result.leftOrNull(),
        )
    }

    @Test
    fun `plant emits SkillRecommended on FARMING when not slotted and the gate fires`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }
        val skills = StubSkillsRegistry().apply {
            recommendOnNext[farming] = 1
        }
        val publisher = RecordingPublisher()

        reducePlantCrop(
            state.body, state.core, WorldCommand.PlantCrop(agent, plotId, wheat),
            StubCropLookup(wheatCrop), plots, agentsRegistry(), skills, SkillProgression(skills, publisher),
            tracker, tick = 1,
        )

        val rec = publisher.events.filterIsInstance<AgentEvent.SkillRecommended>().single()
        assertEquals(farming, rec.skill)
    }

    // ─────────────────────────── tend ───────────────────────────

    @Test
    fun `tend happy path bumps last_tended_at_tick, spends stamina, emits CropTended`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot())
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 1, lastTendedAtTick = 1, plantedByAgentId = agent))
        }
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        val result = reduceTendCrop(
            state.body, state.core, WorldCommand.TendCrop(agent, plotId),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            SkillProgression(skills, publisher), tracker, tick = 25,
        )

        val (next, _, events) = assertNotNull(result.getOrNull())
        assertIs<WorldEvent.CropTended>(events.single())
        assertEquals(25L, plots.findById(plotId)?.plant?.lastTendedAtTick)
        assertEquals(26, next.bodyOf(agent)!!.stamina)
    }

    @Test
    fun `tend rejects when the plot is empty`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot()) }

        val result = reduceTendCrop(
            state.body, state.core, WorldCommand.TendCrop(agent, plotId),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()), tracker, tick = 1,
        )

        assertEquals(WorldRejection.PlotEmpty(agent, plotId), result.leftOrNull())
    }

    @Test
    fun `tend rejects when the plot does not exist`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore()

        val result = reduceTendCrop(
            state.body, state.core, WorldCommand.TendCrop(agent, plotId),
            StubCropLookup(wheatCrop), plots, agentsRegistry(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()), tracker, tick = 1,
        )

        assertEquals(WorldRejection.UnknownPlot(agent, plotId), result.leftOrNull())
    }

    // ─────────────────────────── harvest ───────────────────────────

    @Test
    fun `harvest happy path adds yield, clears the plot, spends stamina, emits CropHarvested`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot())
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }
        val skills = StubSkillsRegistry()

        val result = reduceHarvestCrop(
            state.body, state.core, WorldCommand.HarvestCrop(agent, plotId),
            StubCropLookup(wheatCrop), plots, items, agentsRegistry(), skills, StubEquipmentStore(),
            balance, SkillProgression(skills, RecordingPublisher()), CharacterXpProgression.NoOp,
            NoOpTriggeredPassiveDispatcher, tracker, Random(0L), tick = 60,
        )

        val (next, _, events) = assertNotNull(result.getOrNull())
        val event = assertIs<WorldEvent.CropHarvested>(events.single())
        assertEquals(4, event.quantity) // baseYield with skill level 0 + maxLuckBonus 0
        assertEquals(outputItem, event.outputItem)
        assertEquals(4, next.inventoryOf(agent).quantityOf(outputItem))
        assertNull(plots.findById(plotId)?.plant)
        assertEquals(22, next.bodyOf(agent)!!.stamina)
    }

    @Test
    fun `harvest scales yield with FARMING level — gain-per-level applied as floor`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot())
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }
        val skills = StubSkillsRegistry().apply { slot(farming, level = 40) }

        val result = reduceHarvestCrop(
            state.body, state.core, WorldCommand.HarvestCrop(agent, plotId),
            StubCropLookup(wheatCrop), plots, items, agentsRegistry(), skills, StubEquipmentStore(),
            balance, SkillProgression(skills, RecordingPublisher()), CharacterXpProgression.NoOp,
            NoOpTriggeredPassiveDispatcher, tracker, Random(0L), tick = 60,
        )

        val (_, _, events) = assertNotNull(result.getOrNull())
        val event = assertIs<WorldEvent.CropHarvested>(events.single())
        // baseYield 4 + floor(40 * 0.05) = 4 + 2 = 6, no luck bonus.
        assertEquals(6, event.quantity)
    }

    @Test
    fun `harvest adds luck bonus when maxLuckBonus is positive`() {
        val state = stateWith()
        val luckyCrop = wheatCrop.copy(maxLuckBonus = 3)
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot())
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }
        val skills = StubSkillsRegistry()
        // Random(seed=42).nextInt(0, 4) yields 0..3 — pin a seed for determinism.
        val rng = Random(42L)
        val expected = rng.nextInt(0, 4)
        val seededAgain = Random(42L)

        val result = reduceHarvestCrop(
            state.body, state.core, WorldCommand.HarvestCrop(agent, plotId),
            StubCropLookup(luckyCrop), plots, items, agentsRegistry(), skills, StubEquipmentStore(),
            balance, SkillProgression(skills, RecordingPublisher()), CharacterXpProgression.NoOp,
            NoOpTriggeredPassiveDispatcher, tracker, seededAgain, tick = 60,
        )

        val (_, _, events) = assertNotNull(result.getOrNull())
        val event = assertIs<WorldEvent.CropHarvested>(events.single())
        assertEquals(4 + expected, event.quantity)
    }

    @Test
    fun `harvest rejects when the crop is not yet ripe`() {
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot())
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }

        val result = reduceHarvestCrop(
            state.body, state.core, WorldCommand.HarvestCrop(agent, plotId),
            StubCropLookup(wheatCrop), plots, items, agentsRegistry(), StubSkillsRegistry(),
            StubEquipmentStore(), balance,
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()), CharacterXpProgression.NoOp,
            NoOpTriggeredPassiveDispatcher, tracker, Random(0L), tick = 30,
        )

        val rej = assertIs<WorldRejection.CropNotRipe>(result.leftOrNull())
        assertEquals(30L, rej.ticksRemaining)
        // The plot must remain planted on rejection.
        assertNotNull(plots.findById(plotId)?.plant)
    }

    @Test
    fun `harvest rejects when adding the yield would exceed carry cap`() {
        val tightBalance = stubBalance(carryGramsPerStrengthPoint = 1)
        val skinnyAgents = agentsRegistry(strength = 1)
        val state = stateWith()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot())
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }

        val result = reduceHarvestCrop(
            state.body, state.core, WorldCommand.HarvestCrop(agent, plotId),
            StubCropLookup(wheatCrop), plots, items, skinnyAgents, StubSkillsRegistry(),
            StubEquipmentStore(), tightBalance,
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()), CharacterXpProgression.NoOp,
            NoOpTriggeredPassiveDispatcher, tracker, Random(0L), tick = 60,
        )

        assertIs<WorldRejection.OverEncumbered>(result.leftOrNull())
        assertNotNull(plots.findById(plotId)?.plant)
    }

    // ─────────────────────────── stubs ───────────────────────────

    private fun agentsRegistry(strength: Int = 100): AgentRegistry = object : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent) {
            Agent(
                id = id,
                owner = PlayerId(UUID.randomUUID()),
                name = "test",
                attributes = AgentAttributes(strength = strength),
            )
        } else {
            null
        }
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
    }

    private fun itemFor(id: ItemId) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 50,
        maxStack = 200,
    )

    private fun stubBalance(carryGramsPerStrengthPoint: Int = 5_000) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 1
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun carryGramsPerStrengthPoint(): Int = carryGramsPerStrengthPoint
    }

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubCropLookup(vararg crops: Crop) : CropLookup {
        private val byId = crops.associateBy { it.id }
        override fun byId(id: CropId): Crop? = byId[id]
        override fun all(): List<Crop> = byId.values.toList()
    }

    private class InMemoryPlotsStore : AgentPlotsStore {
        private val plots = mutableMapOf<UUID, AgentPlot>()
        override fun insertEmpty(plot: AgentPlot) {
            require(plot.plant == null)
            plots[plot.plotId] = plot
        }
        override fun findById(plotId: UUID): AgentPlot? = plots[plotId]
        override fun findByBuilding(buildingInstanceId: UUID): AgentPlot? =
            plots.values.firstOrNull { it.buildingInstanceId == buildingInstanceId }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<AgentPlot>> =
            plots.values.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun plant(plotId: UUID, crop: PlantedCrop): AgentPlot? {
            val current = plots[plotId] ?: return null
            if (current.plant != null) return null
            val updated = current.copy(plant = crop)
            plots[plotId] = updated
            return updated
        }
        override fun tend(plotId: UUID, tick: Long): AgentPlot? {
            val current = plots[plotId] ?: return null
            val planted = current.plant ?: return null
            val updated = current.copy(plant = planted.copy(lastTendedAtTick = tick))
            plots[plotId] = updated
            return updated
        }
        override fun clearPlanting(plotId: UUID): AgentPlot? {
            val current = plots[plotId] ?: return null
            if (current.plant == null) return null
            val updated = current.copy(plant = null)
            plots[plotId] = updated
            return updated
        }
        override fun listPlantedSnapshot(): List<AgentPlot> =
            plots.values.filter { it.plant != null }
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val slottedSkills = mutableMapOf<SkillId, Int>()
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendOnNext = mutableMapOf<SkillId, Int?>()

        fun slot(skill: SkillId, level: Int = 0) {
            slottedSkills[skill] = level
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(
                perSkill = slottedSkills.entries.associate { (id, lvl) ->
                    id to AgentSkillState(id, xp = 0, level = lvl, slotIndex = 0, recommendCount = 0)
                },
                slotCount = 8,
                slotsFilled = slottedSkills.size,
            )

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            if (skill !in slottedSkills) return AddXpResult.Unslotted
            xpAddCalls += skill to delta
            return AddXpResult.Accrued(emptyList())
        }

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? {
            if (skill in slottedSkills) return null
            return recommendOnNext.remove(skill)
        }

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? {
            slottedSkills[skill] = 0
            return null
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class StubEquipmentStore(
        private val equipped: Map<EquipSlot, ItemInstance.Equipment> = emptyMap(),
    ) : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = equipped
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? =
            error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }
}
