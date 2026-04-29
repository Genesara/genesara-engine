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
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Online-vs-offline asymmetry of the sleep gauge. Online agents (membership in
 * `WorldState.positions`) drain sleep at the configured per-tick rate; offline agents
 * regen at [BalanceLookup.sleepRegenPerOfflineTick].
 */
class SleepPassiveTest {

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
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    private fun state(online: Boolean, sleep: Int, hp: Int = 100) = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to node),
        positions = if (online) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(
            agent to AgentBody(
                hp = hp, maxHp = 100,
                stamina = 50, maxStamina = 50,
                mana = 0, maxMana = 0,
                hunger = 80, maxHunger = 100,
                thirst = 80, maxThirst = 100,
                sleep = sleep, maxSleep = 100,
            ),
        ),
        inventories = emptyMap(),
    )

    @Test
    fun `online agent drains sleep at the configured rate`() {
        // Drain only — disable hunger/thirst drain, no stamina regen, no starvation damage,
        // so the only delta is from sleepPassive.
        val (next, _) = applyPassives(
            state(online = true, sleep = 60),
            balance(sleepDrain = 1, sleepRegen = 0),
            tick = 1,
            passives = listOf(sleepPassive),
        )

        assertEquals(59, next.bodies[agent]!!.sleep)
    }

    @Test
    fun `offline agent regens sleep at the configured rate`() {
        val (next, _) = applyPassives(
            state(online = false, sleep = 60),
            balance(sleepDrain = 1, sleepRegen = 2),
            tick = 1,
            passives = listOf(sleepPassive),
        )

        assertEquals(62, next.bodies[agent]!!.sleep)
    }

    @Test
    fun `offline regen clamps at max sleep`() {
        val (next, _) = applyPassives(
            state(online = false, sleep = 99),
            balance(sleepDrain = 1, sleepRegen = 5),
            tick = 1,
            passives = listOf(sleepPassive),
        )

        assertEquals(100, next.bodies[agent]!!.sleep)
    }

    @Test
    fun `online drain clamps at zero`() {
        val (next, _) = applyPassives(
            state(online = true, sleep = 0),
            balance(sleepDrain = 1, sleepRegen = 0),
            tick = 1,
            passives = listOf(sleepPassive),
        )

        assertEquals(0, next.bodies[agent]!!.sleep)
    }

    @Test
    fun `offline body recovering from zero sleep stops taking starvation damage once sleep is positive`() {
        // Full passive list, so starvationDamagePassive runs alongside sleepPassive. With
        // sleep starting at 0 and regen of 5, after one tick sleep is 5 (no longer starving).
        // The damage should fire ONCE this tick (sleep was 0 at the start) — the next tick,
        // sleep is positive and damage stops.
        val initial = state(online = false, sleep = 0, hp = 50)
        val balance = balance(sleepDrain = 0, sleepRegen = 5, starvationDamage = 2)
        val (afterTickOne, _) = applyPassives(initial, balance, tick = 1)
        // First tick: sleep was 0, body is starving → -2 HP. Sleep regens to 5.
        assertEquals(48, afterTickOne.bodies[agent]!!.hp)
        assertEquals(5, afterTickOne.bodies[agent]!!.sleep)

        val (afterTickTwo, _) = applyPassives(afterTickOne, balance, tick = 2)
        // Second tick: sleep is 5 (>0), no starvation damage; regen brings sleep to 10.
        assertEquals(48, afterTickTwo.bodies[agent]!!.hp)
        assertEquals(10, afterTickTwo.bodies[agent]!!.sleep)
    }

    @Test
    fun `gaugeDrainPassive no longer touches sleep`() {
        // Run only gaugeDrainPassive — sleep must be unchanged.
        val (next, _) = applyPassives(
            state(online = true, sleep = 60),
            balance(sleepDrain = 99, sleepRegen = 99),
            tick = 1,
            passives = listOf(gaugeDrainPassive),
        )

        assertEquals(60, next.bodies[agent]!!.sleep)
        // Hunger and thirst still drain (defaults of 1 from the balance helper).
        assertEquals(79, next.bodies[agent]!!.hunger)
        assertEquals(79, next.bodies[agent]!!.thirst)
    }

    private fun balance(
        sleepDrain: Int,
        sleepRegen: Int,
        hungerThirstDrain: Int = 1,
        starvationDamage: Int = 0,
    ) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun gatherablesIn(terrain: Terrain): List<ItemId> = emptyList()
        override fun gatherStaminaCost(item: ItemId): Int = 5
        override fun gatherYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = when (gauge) {
            Gauge.SLEEP -> sleepDrain
            else -> hungerThirstDrain
        }
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = starvationDamage
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = sleepRegen
    }
}
