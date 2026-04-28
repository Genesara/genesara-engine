package dev.gvart.genesara.world.internal.passive

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
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
import kotlin.test.assertNotNull

class SurvivalPassivesTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
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

    private fun stateWith(body: AgentBody) = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())),
        positions = mapOf(agent to nodeId),
        bodies = mapOf(agent to body),
        inventories = emptyMap(),
    )

    private fun body(hp: Int = 100, stamina: Int = 50, hunger: Int = 80, thirst: Int = 80, sleep: Int = 80) =
        AgentBody(
            hp = hp, maxHp = 100,
            stamina = stamina, maxStamina = 50,
            mana = 0, maxMana = 0,
            hunger = hunger, maxHunger = 100,
            thirst = thirst, maxThirst = 100,
            sleep = sleep, maxSleep = 100,
        )

    private fun balance(
        regen: Int = 1,
        drain: Int = 1,
        threshold: Int = 25,
        starvationDamage: Int = 2,
    ) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = regen
        override fun gatherablesIn(terrain: Terrain): List<ItemId> = emptyList()
        override fun gatherStaminaCost(item: ItemId): Int = 5
        override fun gatherYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = drain
        override fun gaugeLowThreshold(gauge: Gauge): Int = threshold
        override fun starvationDamagePerTick(): Int = starvationDamage
    }

    @Test
    fun `gauges drain by the configured amount each tick`() {
        val (next, event) = applyPassives(stateWith(body(stamina = 50)), balance(regen = 0), tick = 1)

        val nextBody = next.bodies[agent]!!
        assertEquals(79, nextBody.hunger)
        assertEquals(79, nextBody.thirst)
        assertEquals(79, nextBody.sleep)
        val applied = assertNotNull(event)
        val delta = applied.deltas[agent]!!
        assertEquals(-1, delta.hunger)
        assertEquals(-1, delta.thirst)
        assertEquals(-1, delta.sleep)
    }

    @Test
    fun `stamina regen halts when any gauge is at or below the low threshold`() {
        val starving = body(stamina = 30, hunger = 25) // threshold == 25
        val (next, _) = applyPassives(stateWith(starving), balance(regen = 1), tick = 1)

        // Stamina regen cancelled — the only delta is the gauge drain.
        assertEquals(30, next.bodies[agent]!!.stamina)
    }

    @Test
    fun `body takes starvation damage every tick when any gauge is zero`() {
        val starving = body(hp = 50, hunger = 0)
        val (next, event) = applyPassives(stateWith(starving), balance(regen = 0, drain = 0), tick = 1)

        assertEquals(48, next.bodies[agent]!!.hp) // -2 starvation damage
        val applied = assertNotNull(event)
        assertEquals(-2, applied.deltas[agent]!!.hp)
    }

    @Test
    fun `healthy agent gets stamina regen plus gauge drain in a single tick`() {
        val healthy = body(stamina = 30) // hunger/thirst/sleep at 80; well above threshold
        val (next, _) = applyPassives(stateWith(healthy), balance(regen = 1), tick = 1)

        val nextBody = next.bodies[agent]!!
        assertEquals(31, nextBody.stamina)  // regen applied
        assertEquals(79, nextBody.hunger)   // drain applied
    }
}
