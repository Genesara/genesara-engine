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
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

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
        inventories = emptyMap(),
    )

    @Test
    fun `removes agent from positions, emits AgentDespawned with prior node and causedBy`() {
        val command = CoreCommand.UnspawnAgent(agent)
        val result = reduceUnspawn(baseWorld.core, command, tick = 7)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { out ->
                assertNull(out.sliceDelta.positions[agent])
                assertEquals(
                    CoreEvent.AgentDespawned(agent, home, tick = 7, causedBy = command.commandId),
                    out.events.single(),
                )
            },
        )
    }

    @Test
    fun `rejects unspawn when agent is not in the world`() {
        val empty = baseWorld.copy(core = baseWorld.core.copy(positions = emptyMap()))
        val result = reduceUnspawn(empty.core, CoreCommand.UnspawnAgent(agent), tick = 1)

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }
}
