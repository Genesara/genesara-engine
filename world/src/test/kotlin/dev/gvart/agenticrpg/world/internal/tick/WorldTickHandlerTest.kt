package dev.gvart.agenticrpg.world.internal.tick

import dev.gvart.agenticrpg.engine.Tick
import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.player.AgentProfile
import dev.gvart.agenticrpg.player.AgentProfileLookup
import dev.gvart.agenticrpg.world.Biome
import dev.gvart.agenticrpg.world.Climate
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.RegionId
import dev.gvart.agenticrpg.world.Terrain
import dev.gvart.agenticrpg.world.Vec3
import dev.gvart.agenticrpg.world.WorldId
import dev.gvart.agenticrpg.world.commands.WorldCommand
import dev.gvart.agenticrpg.world.events.WorldEvent
import dev.gvart.agenticrpg.world.internal.balance.BalanceLookup
import dev.gvart.agenticrpg.world.internal.body.AgentBody
import dev.gvart.agenticrpg.world.internal.worldstate.WorldState
import dev.gvart.agenticrpg.world.internal.worldstate.WorldStateRepository
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
    )

    private val balance = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
    }

    private val profiles = object : AgentProfileLookup {
        override fun find(id: AgentId): AgentProfile = AgentProfile(id, maxHp = 100, maxStamina = 50, maxMana = 0)
    }

    @Test
    fun `accepted commands flow through reduce, persist, and publish`() {
        val repo = RecordingRepository(initial = baseState)
        val queue = CommandQueue()
        val publisher = RecordingPublisher()

        queue.submit(WorldCommand.MoveAgent(agent, northId), appliesAtTick = 7)
        val handler = WorldTickHandler(queue, repo, publisher, balance, profiles)

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
        val handler = WorldTickHandler(queue, repo, publisher, balance, profiles)

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
        }
        val handler = WorldTickHandler(queue, repo, publisher, regen, profiles)

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
        val handler = WorldTickHandler(queue, repo, publisher, balance, profiles)

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
}
