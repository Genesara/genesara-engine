package dev.gvart.genesara.world.internal.spawn

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
import kotlin.test.assertNotNull

class SpawnReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val worldId = WorldId(1L)
    private val region = RegionId(1L)
    private val home = NodeId(1L)

    private val world = WorldState(
        regions = mapOf(
            region to Region(
                id = region,
                worldId = worldId,
                sphereIndex = 0,
                biome = Biome.PLAINS,
                climate = Climate.OCEANIC,
                centroid = Vec3(0.0, 0.0, 1.0),
                faceVertices = emptyList(),
                neighbors = emptySet(),
            ),
        ),
        nodes = mapOf(home to Node(home, region, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())),
        positions = emptyMap(),
        bodies = emptyMap(),
        inventories = emptyMap(),
    )

    private val profile = AgentProfile(agent, maxHp = 100, maxStamina = 50, maxMana = 0)
    private val profiles = profileLookup(profile)

    @Test
    fun `spawns agent at node, initializes body from profile, emits AgentSpawned`() {
        val command = WorldCommand.SpawnAgent(agent, home)
        val result = reduceSpawn(world, command, profiles, tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, event) ->
                assertEquals(home, next.positions[agent])
                val body = assertNotNull(next.bodyOf(agent))
                assertEquals(100, body.hp)
                assertEquals(100, body.maxHp)
                assertEquals(50, body.stamina)
                assertEquals(50, body.maxStamina)
                assertEquals(
                    WorldEvent.AgentSpawned(agent, home, tick = 1, causedBy = command.commandId),
                    event,
                )
            },
        )
    }

    @Test
    fun `rejects spawn when agent already spawned`() {
        val already = world.copy(positions = mapOf(agent to home))
        val result = reduceSpawn(already, WorldCommand.SpawnAgent(agent, home), profiles, tick = 1)

        assertEquals(WorldRejection.AlreadySpawned(agent), result.leftOrNull())
    }

    @Test
    fun `rejects spawn at unknown node`() {
        val ghost = NodeId(99L)
        val result = reduceSpawn(world, WorldCommand.SpawnAgent(agent, ghost), profiles, tick = 1)

        assertEquals(WorldRejection.UnknownNode(ghost), result.leftOrNull())
    }

    @Test
    fun `resumes existing body on respawn instead of resetting from profile`() {
        // Persisted body from a prior session: HP/stamina lower than profile defaults
        val survivor = AgentBody(hp = 30, maxHp = 100, stamina = 5, maxStamina = 50, mana = 0, maxMana = 0)
        val resumed = world.copy(bodies = mapOf(agent to survivor))

        val command = WorldCommand.SpawnAgent(agent, home)
        val result = reduceSpawn(resumed, command, profiles, tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, _) ->
                val body = assertNotNull(next.bodyOf(agent))
                assertEquals(survivor, body) // not refreshed from profile
            },
        )
    }

    @Test
    fun `rejects spawn when profile is missing`() {
        val empty = profileLookup()
        val result = reduceSpawn(world, WorldCommand.SpawnAgent(agent, home), empty, tick = 1)

        assertEquals(WorldRejection.UnknownProfile(agent), result.leftOrNull())
    }

    private fun profileLookup(vararg entries: AgentProfile) = object : AgentProfileLookup {
        private val map = entries.associateBy { it.id }
        override fun find(id: AgentId): AgentProfile? = map[id]
    }
}
