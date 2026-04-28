package dev.gvart.genesara.world.internal.spawn

import dev.gvart.genesara.player.AgentId
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
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnspawnReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val worldId = WorldId(1L)
    private val region = RegionId(1L)
    private val home = NodeId(1L)

    private val baseWorld = WorldState(
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
        positions = mapOf(agent to home),
        bodies = emptyMap(),
    )

    @Test
    fun `removes agent from positions, emits AgentDespawned with prior node and causedBy`() {
        val command = WorldCommand.UnspawnAgent(agent)
        val result = reduceUnspawn(baseWorld, command, tick = 7)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, event) ->
                assertNull(next.positions[agent])
                assertEquals(
                    WorldEvent.AgentDespawned(agent, home, tick = 7, causedBy = command.commandId),
                    event,
                )
            },
        )
    }

    @Test
    fun `rejects unspawn when agent is not in the world`() {
        val empty = baseWorld.copy(positions = emptyMap())
        val result = reduceUnspawn(empty, WorldCommand.UnspawnAgent(agent), tick = 1)

        assertEquals(WorldRejection.UnknownAgent(agent), result.leftOrNull())
    }
}
