package dev.gvart.genesara.world.internal.death

import arrow.core.Either
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
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
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RespawnReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val deathNodeId = NodeId(10L)
    private val checkpointNodeId = NodeId(20L)
    private val starterNodeId = NodeId(30L)

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )
    private val deathNode = Node(deathNodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val checkpointNode = Node(checkpointNodeId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val starterNode = Node(starterNodeId, regionId, q = 2, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    private val profile = AgentProfile(id = agent, maxHp = 100, maxStamina = 80, maxMana = 0)
    private val profiles = StubProfileLookup(mapOf(agent to profile))

    @Test
    fun `respawn restores body to full and places agent at the checkpoint when set`() {
        val state = deadState()
        val resolver = StubResolver(SafeNodeResolution(checkpointNodeId, fromCheckpoint = true))

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, RecordingGateway(), resolver, tick = 5)

        val (next, event) = assertIs<Either.Right<Pair<WorldState, WorldEvent>>>(result).value
        assertEquals(checkpointNodeId, next.positions[agent])
        val body = assertNotNull(next.bodyOf(agent))
        assertEquals(100, body.hp, "HP should be fully restored")
        assertEquals(80, body.stamina)
        assertEquals(AgentBody.DEFAULT_MAX_HUNGER, body.hunger)
        val respawned = assertIs<WorldEvent.AgentRespawned>(event)
        assertEquals(checkpointNodeId, respawned.at)
        assertEquals(true, respawned.fromCheckpoint)
    }

    @Test
    fun `respawn falls back to starter node and reports fromCheckpoint=false`() {
        val state = deadState()
        val resolver = StubResolver(SafeNodeResolution(starterNodeId, fromCheckpoint = false))

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, RecordingGateway(), resolver, tick = 5)

        val (_, event) = assertIs<Either.Right<Pair<WorldState, WorldEvent>>>(result).value
        val respawned = assertIs<WorldEvent.AgentRespawned>(event)
        assertEquals(starterNodeId, respawned.at)
        assertEquals(false, respawned.fromCheckpoint)
    }

    @Test
    fun `respawn rejects when the agent is alive (HP greater than 0)`() {
        val state = stateWith(hp = 50, positioned = true)
        val resolver = StubResolver(SafeNodeResolution(checkpointNodeId, fromCheckpoint = true))

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, RecordingGateway(), resolver, tick = 1)

        assertEquals(WorldRejection.NotDead(agent), result.leftOrNull())
    }

    @Test
    fun `respawn rejects when the agent is at HP=0 but still positioned (death sweep failed)`() {
        // Defensive guard. The death sweep should have removed them from
        // positions; if we still see HP=0 + positioned, something upstream
        // missed them and respawn would re-position incorrectly.
        val state = stateWith(hp = 0, positioned = true)
        val resolver = StubResolver(SafeNodeResolution(checkpointNodeId, fromCheckpoint = true))

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, RecordingGateway(), resolver, tick = 1)

        assertEquals(WorldRejection.NotDead(agent), result.leftOrNull())
    }

    @Test
    fun `respawn rejects when the agent has no body at all`() {
        // Never-spawned agent.
        val state = stateWith(hp = null, positioned = false)
        val resolver = StubResolver(SafeNodeResolution(checkpointNodeId, fromCheckpoint = true))

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, RecordingGateway(), resolver, tick = 1)

        assertEquals(WorldRejection.NotDead(agent), result.leftOrNull())
    }

    @Test
    fun `respawn rejects when the profile is missing — state corruption`() {
        val emptyProfiles = StubProfileLookup(emptyMap())
        val state = deadState()
        val resolver = StubResolver(SafeNodeResolution(checkpointNodeId, fromCheckpoint = true))

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), emptyProfiles, RecordingGateway(), resolver, tick = 1)

        assertEquals(WorldRejection.UnknownProfile(agent), result.leftOrNull())
    }

    @Test
    fun `respawn rejects when the resolver returns no candidate (misconfigured world)`() {
        val state = deadState()
        val resolver = StubResolver(null)

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, RecordingGateway(), resolver, tick = 1)

        // Misconfigured world surfaces as a typed rejection naming the agent.
        assertEquals(WorldRejection.NoSpawnableNode(agent), result.leftOrNull())
    }

    @Test
    fun `stale checkpoint self-heals — gateway is cleared and resolver re-runs`() {
        // Admin deleted the checkpoint node between set and respawn but the
        // safe-node row still references it (DB cascade hasn't fired, or the
        // state's nodes map and the DB are out of sync). The reducer must
        // wipe the stale checkpoint and fall through to the starter rather
        // than dead-end the agent.
        val state = deadStateWithoutNodes(checkpointNodeId)
        val gateway = RecordingGateway()
        val resolver = SequencedResolver(
            // First call returns the (stale) checkpoint; second call (after
            // gateway clear) falls through to the starter.
            listOf(
                SafeNodeResolution(checkpointNodeId, fromCheckpoint = true),
                SafeNodeResolution(starterNodeId, fromCheckpoint = false),
            ),
        )

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, gateway, resolver, tick = 5)

        val (next, event) = assertIs<Either.Right<Pair<WorldState, WorldEvent>>>(result).value
        val respawned = assertIs<WorldEvent.AgentRespawned>(event)
        assertEquals(starterNodeId, respawned.at)
        assertEquals(false, respawned.fromCheckpoint)
        assertEquals(starterNodeId, next.positions[agent])
        assertEquals(listOf(agent), gateway.cleared, "stale checkpoint must be cleared on self-heal")
    }

    @Test
    fun `stale non-checkpoint resolution surfaces as NoSpawnableNode (no infinite re-resolve)`() {
        // Defensive: if the resolver hands us a stale starter / random node,
        // we don't loop — surface as NoSpawnableNode rather than retry.
        val state = deadStateWithoutNodes(starterNodeId)
        val resolver = StubResolver(SafeNodeResolution(starterNodeId, fromCheckpoint = false))

        val result = reduceRespawn(state, WorldCommand.Respawn(agent), profiles, RecordingGateway(), resolver, tick = 1)

        assertEquals(WorldRejection.NoSpawnableNode(agent), result.leftOrNull())
    }

    private fun deadState(): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(deathNodeId to deathNode, checkpointNodeId to checkpointNode, starterNodeId to starterNode),
        positions = emptyMap(),  // dead = unpositioned
        bodies = mapOf(agent to body(hp = 0)),
        inventories = emptyMap(),
    )

    private fun deadStateWithoutNodes(missing: NodeId): WorldState = deadState().let { s ->
        s.copy(nodes = s.nodes - missing)
    }

    private fun stateWith(hp: Int?, positioned: Boolean): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(deathNodeId to deathNode, checkpointNodeId to checkpointNode, starterNodeId to starterNode),
        positions = if (positioned) mapOf(agent to deathNodeId) else emptyMap(),
        bodies = if (hp == null) emptyMap() else mapOf(agent to body(hp)),
        inventories = emptyMap(),
    )

    private fun body(hp: Int) = AgentBody(
        hp = hp, maxHp = 100,
        stamina = 0, maxStamina = 80,
        mana = 0, maxMana = 0,
        hunger = 0, maxHunger = AgentBody.DEFAULT_MAX_HUNGER,
        thirst = 0, maxThirst = AgentBody.DEFAULT_MAX_THIRST,
        sleep = 0, maxSleep = AgentBody.DEFAULT_MAX_SLEEP,
    )

    private class StubProfileLookup(private val byId: Map<AgentId, AgentProfile>) : AgentProfileLookup {
        override fun find(id: AgentId): AgentProfile? = byId[id]
    }

    private class StubResolver(private val resolution: SafeNodeResolution?) : SafeNodeResolver {
        override fun resolveFor(agentId: AgentId): SafeNodeResolution? = resolution
    }

    /**
     * Returns the next scripted resolution per call. Used to verify the
     * reducer's stale-checkpoint self-heal: first call returns the stale
     * checkpoint, second call (after gateway clear) returns the starter.
     */
    private class SequencedResolver(private val script: List<SafeNodeResolution>) : SafeNodeResolver {
        private var index = 0
        override fun resolveFor(agentId: AgentId): SafeNodeResolution? {
            if (index >= script.size) return null
            return script[index++]
        }
    }

    private class RecordingGateway : dev.gvart.genesara.world.AgentSafeNodeGateway {
        val sets = mutableListOf<Triple<AgentId, NodeId, Long>>()
        val cleared = mutableListOf<AgentId>()
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {
            sets += Triple(agentId, nodeId, tick)
        }
        override fun find(agentId: AgentId): NodeId? = null
        override fun clear(agentId: AgentId) {
            cleared += agentId
        }
    }
}
