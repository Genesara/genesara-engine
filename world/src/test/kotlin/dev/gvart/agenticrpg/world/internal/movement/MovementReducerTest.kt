package dev.gvart.agenticrpg.world.internal.movement

import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.Biome
import dev.gvart.agenticrpg.world.Climate
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.RegionId
import dev.gvart.agenticrpg.world.Terrain
import dev.gvart.agenticrpg.world.Vec3
import dev.gvart.agenticrpg.world.WorldId
import dev.gvart.agenticrpg.world.WorldRejection
import dev.gvart.agenticrpg.world.commands.WorldCommand
import dev.gvart.agenticrpg.world.events.WorldEvent
import dev.gvart.agenticrpg.world.internal.balance.BalanceLookup
import dev.gvart.agenticrpg.world.internal.body.AgentBody
import dev.gvart.agenticrpg.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovementReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val worldId = WorldId(1L)
    private val region = RegionId(1L)
    private val a = NodeId(1L)
    private val b = NodeId(2L)
    private val c = NodeId(3L)

    private val flatCost = balanceLookup(cost = 1)

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
        nodes = mapOf(
            a to Node(a, region, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(b)),
            b to Node(b, region, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(a, c)),
            c to Node(c, region, q = 2, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(b)),
        ),
        positions = mapOf(agent to a),
        bodies = mapOf(agent to body(stamina = 10, maxStamina = 10)),
    )

    @Test
    fun `accepts move to adjacent node, deducts stamina, and emits AgentMoved`() {
        val command = WorldCommand.MoveAgent(agent, b)
        val result = reduceMove(world, command, flatCost, tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, event) ->
                assertEquals(b, next.positions[agent])
                assertEquals(9, next.bodyOf(agent)!!.stamina)
                assertEquals(
                    WorldEvent.AgentMoved(agent, a, b, tick = 1, causedBy = command.commandId),
                    event,
                )
            },
        )
    }

    @Test
    fun `rejects move when agent is unknown`() {
        val unknown = AgentId(UUID.randomUUID())
        val result = reduceMove(world, WorldCommand.MoveAgent(unknown, b), flatCost, tick = 1)

        assertTrue(result.isLeft())
        assertEquals(WorldRejection.UnknownAgent(unknown), result.leftOrNull())
    }

    @Test
    fun `rejects move to unknown node`() {
        val ghost = NodeId(99L)
        val result = reduceMove(world, WorldCommand.MoveAgent(agent, ghost), flatCost, tick = 1)

        assertEquals(WorldRejection.UnknownNode(ghost), result.leftOrNull())
    }

    @Test
    fun `rejects move to non-adjacent node`() {
        val result = reduceMove(world, WorldCommand.MoveAgent(agent, c), flatCost, tick = 1)

        assertEquals(WorldRejection.NotAdjacent(a, c), result.leftOrNull())
    }

    @Test
    fun `rejects move when stamina is below cost`() {
        val expensive = balanceLookup(cost = 99)
        val result = reduceMove(world, WorldCommand.MoveAgent(agent, b), expensive, tick = 1)

        assertEquals(
            WorldRejection.NotEnoughStamina(agent, required = 99, available = 10),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects move into an unpainted region`() {
        val unpainted = world.copy(
            regions = world.regions.mapValues { (_, r) -> r.copy(biome = null) },
        )
        val result = reduceMove(unpainted, WorldCommand.MoveAgent(agent, b), flatCost, tick = 1)

        assertEquals(WorldRejection.UnpaintedRegion(region), result.leftOrNull())
    }

    private fun balanceLookup(cost: Int) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = cost
        override fun staminaRegenPerTick(climate: Climate) = 0
    }

    private fun body(stamina: Int, maxStamina: Int) = AgentBody(
        hp = 10, maxHp = 10,
        stamina = stamina, maxStamina = maxStamina,
        mana = 0, maxMana = 0,
    )
}
