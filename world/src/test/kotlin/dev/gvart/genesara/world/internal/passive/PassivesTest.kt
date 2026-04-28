package dev.gvart.genesara.world.internal.passive

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PassivesTest {

    private val agent = AgentId(UUID.randomUUID())
    private val worldId = WorldId(1L)
    private val region = RegionId(1L)
    private val node = NodeId(1L)

    private fun world(stamina: Int, maxStamina: Int = 10, climate: Climate? = Climate.OCEANIC) = WorldState(
        regions = mapOf(
            region to Region(
                id = region,
                worldId = worldId,
                sphereIndex = 0,
                biome = Biome.PLAINS,
                climate = climate,
                centroid = Vec3(0.0, 0.0, 1.0),
                faceVertices = emptyList(),
                neighbors = emptySet(),
            ),
        ),
        nodes = mapOf(node to Node(node, region, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())),
        positions = mapOf(agent to node),
        bodies = mapOf(
            agent to AgentBody(
                hp = 10, maxHp = 10,
                stamina = stamina, maxStamina = maxStamina,
                mana = 0, maxMana = 0,
            ),
        ),
        inventories = emptyMap(),
    )

    private val regenOne = balanceLookup(regen = 1)
    private val regenZero = balanceLookup(regen = 0)

    @Test
    fun `regenerates stamina and emits one PassivesApplied event`() {
        val (next, event) = applyPassives(world(stamina = 5), regenOne, tick = 1)

        assertEquals(6, next.bodyOf(agent)!!.stamina)
        assertEquals(
            WorldEvent.PassivesApplied(mapOf(agent to BodyDelta(stamina = 1)), tick = 1),
            event,
        )
    }

    @Test
    fun `clamps regen at maxStamina`() {
        // body at 9/10, regen 5 => effective +1
        val (next, event) = applyPassives(world(stamina = 9), balanceLookup(regen = 5), tick = 1)

        assertEquals(10, next.bodyOf(agent)!!.stamina)
        assertEquals(
            WorldEvent.PassivesApplied(mapOf(agent to BodyDelta(stamina = 1)), tick = 1),
            event,
        )
    }

    @Test
    fun `skips event entirely when no agent has any non-zero delta`() {
        val (next, event) = applyPassives(world(stamina = 10), regenOne, tick = 1)

        assertEquals(world(stamina = 10), next)
        assertNull(event)
    }

    @Test
    fun `skips event when balance lookup returns 0`() {
        val (next, event) = applyPassives(world(stamina = 5), regenZero, tick = 1)

        assertEquals(world(stamina = 5), next)
        assertNull(event)
    }

    @Test
    fun `skips regen when region climate is null`() {
        val (next, event) = applyPassives(world(stamina = 5, climate = null), regenOne, tick = 1)

        assertEquals(world(stamina = 5, climate = null), next)
        assertNull(event)
    }

    private fun balanceLookup(regen: Int) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = regen
        override fun gatherablesIn(terrain: Terrain): List<dev.gvart.genesara.world.ItemId> = emptyList()
        override fun gatherStaminaCost(item: dev.gvart.genesara.world.ItemId): Int = 5
        override fun gatherYield(item: dev.gvart.genesara.world.ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
    }
}
