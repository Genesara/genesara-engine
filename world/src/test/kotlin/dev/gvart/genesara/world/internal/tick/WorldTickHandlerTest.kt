package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.engine.Tick
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
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
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.WorldStateRepository
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldTickHandlerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val homeId = NodeId(1L)
    private val northId = NodeId(2L)
    private val ghostId = NodeId(99L)

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
        override fun gatherStaminaCost(item: ItemId): Int = 5
        override fun gatherYield(item: ItemId): Int = 1
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
        val queue = CommandQueue()
        val publisher = RecordingPublisher()

        queue.submit(WorldCommand.MoveAgent(agent, northId), appliesAtTick = 7)
        val handler = WorldTickHandler(queue, repo, publisher, balance, profiles, items, NoopResourceStore, NoopSkillsRegistry, NoopAgentRegistry, NoopEquipmentStore, NoopSafeNodeGateway, NoopSafeNodeResolver, NoopBuildingsStore, EmptyBuildingsCatalog)

        handler.onTick(Tick(7, Instant.parse("2026-01-01T00:00:00Z")))

        // Persisted exactly once with the post-move state.
        val saved = assertNotNull(repo.lastSaved)
        assertEquals(northId, saved.positions[agent])
        // Published an AgentMoved event.
        val moved = publisher.events.filterIsInstance<WorldEvent.AgentMoved>().single()
        assertEquals(homeId, moved.from)
        assertEquals(northId, moved.to)
        assertEquals(7L, moved.tick)
    }

    @Test
    fun `rejected commands are skipped, state stays put, and no event is published`() {
        val repo = RecordingRepository(initial = baseState)
        val queue = CommandQueue()
        val publisher = RecordingPublisher()

        queue.submit(WorldCommand.MoveAgent(agent, ghostId), appliesAtTick = 1)
        val handler = WorldTickHandler(queue, repo, publisher, balance, profiles, items, NoopResourceStore, NoopSkillsRegistry, NoopAgentRegistry, NoopEquipmentStore, NoopSafeNodeGateway, NoopSafeNodeResolver, NoopBuildingsStore, EmptyBuildingsCatalog)

        handler.onTick(Tick(1, Instant.parse("2026-01-01T00:00:00Z")))

        val saved = assertNotNull(repo.lastSaved)
        assertEquals(homeId, saved.positions[agent])
        assertTrue(publisher.events.none { it is WorldEvent.AgentMoved })
    }

    @Test
    fun `applyPassives publishes a PassivesApplied event when stamina regenerates`() {
        // Body below max stamina; balance returns +1 regen → passives event is emitted.
        val below = baseState.copy(bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 100, stamina = 10, maxStamina = 50, mana = 0, maxMana = 0)))
        val repo = RecordingRepository(initial = below)
        val queue = CommandQueue()
        val publisher = RecordingPublisher()
        val regen = object : BalanceLookup {
            override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
            override fun staminaRegenPerTick(climate: Climate) = 1
            override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
            override fun gatherStaminaCost(item: ItemId): Int = 5
            override fun gatherYield(item: ItemId): Int = 1
            override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
            override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
            override fun starvationDamagePerTick(): Int = 0
            override fun isWaterSource(terrain: Terrain): Boolean = false
            override fun drinkStaminaCost(): Int = 1
            override fun drinkThirstRefill(): Int = 25
            override fun sleepRegenPerOfflineTick(): Int = 0
            override fun isTraversable(terrain: Terrain): Boolean = true
        }
        val handler = WorldTickHandler(queue, repo, publisher, regen, profiles, items, NoopResourceStore, NoopSkillsRegistry, NoopAgentRegistry, NoopEquipmentStore, NoopSafeNodeGateway, NoopSafeNodeResolver, NoopBuildingsStore, EmptyBuildingsCatalog)

        handler.onTick(Tick(2, Instant.parse("2026-01-01T00:00:00Z")))

        val passives = publisher.events.filterIsInstance<WorldEvent.PassivesApplied>().single()
        assertEquals(2L, passives.tick)
        // Body persisted with regenerated stamina.
        assertEquals(11, repo.lastSaved!!.bodies[agent]!!.stamina)
    }

    @Test
    fun `commands targeted at other ticks are not drained for this tick`() {
        val repo = RecordingRepository(initial = baseState)
        val queue = CommandQueue()
        val publisher = RecordingPublisher()
        queue.submit(WorldCommand.MoveAgent(agent, northId), appliesAtTick = 99)
        val handler = WorldTickHandler(queue, repo, publisher, balance, profiles, items, NoopResourceStore, NoopSkillsRegistry, NoopAgentRegistry, NoopEquipmentStore, NoopSafeNodeGateway, NoopSafeNodeResolver, NoopBuildingsStore, EmptyBuildingsCatalog)

        handler.onTick(Tick(7, Instant.parse("2026-01-01T00:00:00Z")))

        // Tick 7 had no commands → only passives were considered, and with regen=0 nothing was published.
        assertTrue(publisher.events.none { it is WorldEvent.AgentMoved })
        // The command should still be drainable for tick 99.
        assertEquals(1, queue.drainFor(99).size)
    }

    private class RecordingRepository(private val initial: WorldState) : WorldStateRepository {
        var lastSaved: WorldState? = null
        override fun load(): WorldState = initial
        override fun save(state: WorldState) {
            lastSaved = state
        }
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

    /**
     * Resource store with no rows for any node — gather commands here return
     * `ResourceNotAvailableHere` from the reducer, which is fine: this test exercises
     * the move + passive paths, not gather.
     */
    private object NoopResourceStore : dev.gvart.genesara.world.internal.resources.NodeResourceStore {
        override fun read(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources.EMPTY
        override fun availability(nodeId: NodeId, item: ItemId, tick: Long) = null
        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {
            error("NoopResourceStore.decrement should not be called in this test")
        }
        override fun seed(rows: Collection<dev.gvart.genesara.world.internal.resources.InitialResourceRow>, tick: Long) {}
    }

    /**
     * Skills registry stand-in: gather isn't exercised in this test, so addXp /
     * maybeRecommend never fire. snapshot returns an empty 8-slot view if anything
     * does call it.
     */
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

    /**
     * AgentRegistry stand-in for tick tests that don't exercise death. The
     * death sweep only calls `applyDeathPenalty` when a body has hp == 0;
     * these tests keep all bodies above zero so the call never lands here.
     */
    private object NoopAgentRegistry : dev.gvart.genesara.player.AgentRegistry {
        override fun find(id: AgentId): dev.gvart.genesara.player.Agent? = null
        override fun listForOwner(owner: dev.gvart.genesara.account.PlayerId): List<dev.gvart.genesara.player.Agent> = emptyList()
        // Inherits the default-throwing applyDeathPenalty; tests that need it
        // would substitute their own.
    }

    private object NoopEquipmentStore : dev.gvart.genesara.world.EquipmentInstanceStore {
        override fun equippedFor(agentId: AgentId): Map<dev.gvart.genesara.world.EquipSlot, dev.gvart.genesara.world.EquipmentInstance> =
            emptyMap()
        override fun insert(instance: dev.gvart.genesara.world.EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): dev.gvart.genesara.world.EquipmentInstance? = error("not used")
        override fun listByAgent(agentId: AgentId): List<dev.gvart.genesara.world.EquipmentInstance> = error("not used")
        override fun assignToSlot(
            instanceId: UUID,
            agentId: AgentId,
            slot: dev.gvart.genesara.world.EquipSlot,
        ): dev.gvart.genesara.world.EquipmentInstance? = error("not used")
        override fun clearSlot(
            agentId: AgentId,
            slot: dev.gvart.genesara.world.EquipSlot,
        ): dev.gvart.genesara.world.EquipmentInstance? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): dev.gvart.genesara.world.EquipmentInstance? =
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

    private object NoopBuildingsStore : dev.gvart.genesara.world.BuildingsStore {
        override fun insert(building: dev.gvart.genesara.world.Building) = error("not used")
        override fun findById(id: java.util.UUID): dev.gvart.genesara.world.Building? = null
        override fun findInProgress(
            node: NodeId,
            agent: AgentId,
            type: dev.gvart.genesara.world.BuildingType,
        ): dev.gvart.genesara.world.Building? = null
        override fun listAtNode(node: NodeId): List<dev.gvart.genesara.world.Building> = emptyList()
        override fun listByNodes(
            nodes: Set<NodeId>,
        ): Map<NodeId, List<dev.gvart.genesara.world.Building>> = emptyMap()
        override fun advanceProgress(id: java.util.UUID, newProgress: Int, asOfTick: Long): dev.gvart.genesara.world.Building? = null
        override fun complete(id: java.util.UUID, asOfTick: Long): dev.gvart.genesara.world.Building? = null
    }

    private val EmptyBuildingsCatalog: dev.gvart.genesara.world.internal.buildings.BuildingsCatalog =
        dev.gvart.genesara.world.internal.buildings.BuildingsCatalog(
            dev.gvart.genesara.world.internal.buildings.BuildingDefinitionProperties(catalog = emptyMap()),
        )
}
