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
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

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
    fun `spawns agent at resolver target, initializes body from profile, emits AgentSpawned`() {
        val command = CoreCommand.SpawnAgent(agent)
        val result = reduceSpawn(world.core, world.body, command, profiles, fixedResolver(home), tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { out ->
                assertEquals(home, out.sliceDelta.positions[agent])
                val applied = world.copy(core = out.sliceDelta).applyEffects(out.effects)
                val body = assertNotNull(applied.bodyOf(agent))
                assertEquals(100, body.hp)
                assertEquals(100, body.maxHp)
                assertEquals(50, body.stamina)
                assertEquals(50, body.maxStamina)
                assertEquals(
                    CoreEvent.AgentSpawned(agent, home, tick = 1, causedBy = command.commandId),
                    out.events.single(),
                )
            },
        )
    }

    @Test
    fun `rejects spawn when agent already spawned`() {
        val already = world.copy(core = world.core.copy(positions = mapOf(agent to home)))
        val result = reduceSpawn(already.core, already.body, CoreCommand.SpawnAgent(agent), profiles, fixedResolver(home), tick = 1)

        assertEquals(WorldRejection.AlreadySpawned(agent), result.leftOrNull())
    }

    @Test
    fun `rejects with NoSpawnableNode when the resolver returns null`() {
        val result = reduceSpawn(world.core, world.body, CoreCommand.SpawnAgent(agent), profiles, fixedResolver(null), tick = 1)

        assertEquals(WorldRejection.NoSpawnableNode(agent), result.leftOrNull())
    }

    @Test
    fun `rejects with UnknownNode when the resolver returns a node missing from state`() {
        val ghost = NodeId(99L)
        val result = reduceSpawn(world.core, world.body, CoreCommand.SpawnAgent(agent), profiles, fixedResolver(ghost), tick = 1)

        assertEquals(WorldRejection.UnknownNode(ghost), result.leftOrNull())
    }

    @Test
    fun `resumes existing body on respawn instead of resetting from profile`() {
        val survivor = AgentBody(hp = 30, maxHp = 100, stamina = 5, maxStamina = 50, mana = 0, maxMana = 0)
        val resumed = world.copy(body = world.body.copy(bodies = mapOf(agent to survivor)))

        val command = CoreCommand.SpawnAgent(agent)
        val result = reduceSpawn(resumed.core, resumed.body, command, profiles, fixedResolver(home), tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { out ->
                val applied = resumed.copy(core = out.sliceDelta).applyEffects(out.effects)
                val body = assertNotNull(applied.bodyOf(agent))
                assertEquals(survivor, body)
            },
        )
    }

    @Test
    fun `rejects spawn when profile is missing`() {
        val empty = profileLookup()
        val result = reduceSpawn(world.core, world.body, CoreCommand.SpawnAgent(agent), empty, fixedResolver(home), tick = 1)

        assertEquals(WorldRejection.UnknownProfile(agent), result.leftOrNull())
    }

    private fun profileLookup(vararg entries: AgentProfile) = object : AgentProfileLookup {
        private val map = entries.associateBy { it.id }
        override fun find(id: AgentId): AgentProfile? = map[id]
    }

    private fun fixedResolver(target: NodeId?) = object : SpawnLocationResolver {
        override fun resolveFor(agentId: AgentId): NodeId? = target
    }
}
