package dev.gvart.genesara.world.internal.extract

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
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.resources.InitialResourceRow
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtractReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val coal = ItemId("COAL")
    private val wood = ItemId("WOOD")
    private val tracker = InMemoryBehaviorTracker()

    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.MOUNTAIN, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private val balance = balance(staminaCost = 5)
    private val items = StubItemLookup(
        mapOf(
            coal to itemFor(coal, harvestSkill = "MINING", extractionOnly = true),
            wood to itemFor(wood, harvestSkill = "LUMBERJACKING", extractionOnly = false),
        ),
    )
    private val agents: AgentRegistry = StubAgentRegistry(strength = 100)
    private val equipment: AgentItemInstancesStore = StubEquipmentStore()

    private fun stateWith(stamina: Int = 30): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.MOUNTAIN, adjacency = emptySet())),
        positions = mapOf(agent to nodeId),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = stamina, maxStamina = 50, mana = 0, maxMana = 0)),
        inventories = emptyMap(),
    )

    @Test
    fun `happy path — agent at MINE extracts one COAL, spends stamina, emits ResourceExtracted`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(coal to 20))
        val mine = StubBuildingsLookup(activeStationsByHint = mapOf(BuildingCategoryHint.EXTRACTION_MINE to listOf(activeMine())))
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        val result = reduceExtract(
            state.body, state.core, WorldCommand.Extract(agent, coal), balance, items, store, mine, agents, equipment,
            SkillProgression(skills, publisher),
            characterXp = CharacterXpProgression.NoOp,
            scaling = NoScaling, triggeredPassives = NoOpTriggeredPassiveDispatcher,
            behaviorTracker = tracker, tick = 7,
        )

        val (next, _, events) = assertNotNull(result.getOrNull(), "Expected success but got: ${result.leftOrNull()}")
        val event = assertIs<WorldEvent.ResourceExtracted>(events.single())
        assertEquals(coal, event.item)
        assertEquals(1, event.quantity)
        assertEquals(1, next.inventoryOf(agent).quantityOf(coal))
        assertEquals(25, next.bodyOf(agent)!!.stamina)
        assertEquals(19, store.quantity(coal))
    }

    @Test
    fun `rejects when no active MINE on the node`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(coal to 20))
        val empty = StubBuildingsLookup(activeStationsByHint = emptyMap())

        val result = reduceExtract(
            state.body, state.core, WorldCommand.Extract(agent, coal), balance, items, store, empty, agents, equipment,
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            characterXp = CharacterXpProgression.NoOp, scaling = NoScaling,
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
        )

        assertEquals(WorldRejection.ExtractRequiresMine(agent, nodeId), result.leftOrNull())
        assertEquals(20, store.quantity(coal))
    }

    @Test
    fun `rejects when target item is not flagged extractionOnly — use harvest instead`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 50))
        val mine = StubBuildingsLookup(activeStationsByHint = mapOf(BuildingCategoryHint.EXTRACTION_MINE to listOf(activeMine())))

        val result = reduceExtract(
            state.body, state.core, WorldCommand.Extract(agent, wood), balance, items, store, mine, agents, equipment,
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            characterXp = CharacterXpProgression.NoOp, scaling = NoScaling,
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
        )

        assertEquals(WorldRejection.ResourceNotAvailableHere(agent, nodeId, wood), result.leftOrNull())
        assertEquals(50, store.quantity(wood))
    }

    @Test
    fun `rejects when node has no deposit of the requested extraction-only item`() {
        val state = stateWith()
        val store = StubResourceStore(initial = emptyMap())
        val mine = StubBuildingsLookup(activeStationsByHint = mapOf(BuildingCategoryHint.EXTRACTION_MINE to listOf(activeMine())))

        val result = reduceExtract(
            state.body, state.core, WorldCommand.Extract(agent, coal), balance, items, store, mine, agents, equipment,
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            characterXp = CharacterXpProgression.NoOp, scaling = NoScaling,
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
        )

        assertEquals(WorldRejection.ResourceNotAvailableHere(agent, nodeId, coal), result.leftOrNull())
    }

    @Test
    fun `rejects when agent has insufficient stamina`() {
        val state = stateWith(stamina = 2)
        val store = StubResourceStore(initial = mapOf(coal to 20))
        val mine = StubBuildingsLookup(activeStationsByHint = mapOf(BuildingCategoryHint.EXTRACTION_MINE to listOf(activeMine())))

        val result = reduceExtract(
            state.body, state.core, WorldCommand.Extract(agent, coal), balance, items, store, mine, agents, equipment,
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            characterXp = CharacterXpProgression.NoOp, scaling = NoScaling,
            triggeredPassives = NoOpTriggeredPassiveDispatcher, behaviorTracker = tracker, tick = 1,
        )

        val rejection = assertIs<WorldRejection.NotEnoughStamina>(result.leftOrNull())
        assertEquals(5, rejection.required)
        assertEquals(2, rejection.available)
    }

    private fun activeMine(): Building = Building(
        instanceId = UUID.randomUUID(), nodeId = nodeId, type = BuildingType.MINE,
        status = BuildingStatus.ACTIVE, builtByAgentId = agent,
        builtAtTick = 1L, lastProgressTick = 1L, progressSteps = 10, totalSteps = 10,
        hpCurrent = 80, hpMax = 80,
    )

    private fun itemFor(
        id: ItemId,
        harvestSkill: String? = null,
        extractionOnly: Boolean = false,
    ) = Item(
        id = id, displayName = id.value, description = "",
        category = ItemCategory.RESOURCE, weightPerUnit = 100, maxStack = 100,
        harvestSkill = harvestSkill?.let(::SkillId),
        extractionOnly = extractionOnly,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubBuildingsLookup(
        private val activeStationsByHint: Map<BuildingCategoryHint, List<Building>>,
    ) : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> =
            activeStationsByHint[hint].orEmpty()
    }

    private inner class StubResourceStore(initial: Map<ItemId, Int>) : NodeResourceStore {
        private val cells = initial.toMutableMap()
        override fun read(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources(
            cells.mapValues { (k, v) -> dev.gvart.genesara.world.NodeResourceView(k, v, v.coerceAtLeast(1)) },
        )
        override fun availability(nodeId: NodeId, item: ItemId, tick: Long): NodeResourceCell? =
            cells[item]?.let { NodeResourceCell(nodeId, item, it, it.coerceAtLeast(1)) }
        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {
            val have = cells[item] ?: error("no cell for $item")
            check(have >= amount)
            cells[item] = have - amount
        }
        override fun seed(rows: Collection<InitialResourceRow>, tick: Long) = error("not used")
        fun quantity(item: ItemId): Int = cells[item] ?: 0
    }

    private class StubAgentRegistry(private val strength: Int) : AgentRegistry {
        override fun find(id: AgentId): Agent? = Agent(
            id = id, owner = PlayerId(UUID.randomUUID()), name = "test",
            attributes = AgentAttributes(strength = strength, dexterity = 10, constitution = 10, perception = 10, intelligence = 10, luck = 10),
        )
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class StubEquipmentStore : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult =
            AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private fun balance(staminaCost: Int) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain) = emptyList<dev.gvart.genesara.world.ResourceSpawnRule>()
        override fun harvestStaminaCost(item: ItemId): Int = staminaCost
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun roadStaminaMultiplier(): Double = 0.5
        override fun carryGramsPerStrengthPoint(): Int = 1_000_000
    }
}
