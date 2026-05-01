package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.passive.applyPassives
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Glue-level test exercising the seam between [applyPassives] (which damages
 * HP via [starvationDamagePassive][dev.gvart.genesara.world.internal.passive.starvationDamagePassive])
 * and [processDeaths] (which observes HP=0 and emits [WorldEvent.AgentDied])
 * — followed by a [reduceRespawn] that materializes the agent at their
 * checkpoint with body restored.
 *
 * Without this seam-level coverage, a tick-ordering regression
 * (e.g. running death-sweep BEFORE passives instead of after) would slip
 * through the unit-level `DeathSweepTest` / `PassivesTest` because each is
 * tested in isolation.
 */
class StarvationDeathRespawnIntegrationTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val checkpointId = NodeId(2L)

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
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val checkpointNode = Node(checkpointId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    @Test
    fun `starvation damages HP, sweep observes zero, agent dies, respawn restores body`() {
        // Set up: an agent already starving (hunger=0) with HP just above the
        // single-tick starvation damage. One tick of passives drops them to 0.
        val agents = ScriptedRegistry(
            mapOf(
                agent to DeathPenaltyOutcome(
                    xpLost = 0,
                    deleveled = false,
                    attributePointLost = null,
                ),
            ),
        )
        val balance = balance(starvationDamage = 1, xpLoss = 25)
        var state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to node, checkpointId to checkpointNode),
            positions = mapOf(agent to nodeId),
            bodies = mapOf(agent to body(hp = 1, hunger = 0)),
            inventories = emptyMap(),
        )

        // Tick 1: passives apply, HP drops 1 → 0.
        val (afterPassives, passivesEvent) = applyPassives(state, balance, tick = 1L)
        assertNotNull(passivesEvent, "passives should have applied damage")
        assertEquals(0, afterPassives.bodies[agent]?.hp)
        assertTrue(agent in afterPassives.positions, "still positioned just before sweep")

        // Sweep observes HP=0 and removes from positions.
        val (afterDeaths, deathEvents) = processDeaths(afterPassives, balance, agents, tick = 1L)
        val died = assertIs<WorldEvent.AgentDied>(deathEvents.single())
        assertEquals(agent, died.agent)
        assertEquals(nodeId, died.at)
        assertEquals(0, died.xpLost, "fresh agent has 0 xpCurrent → no XP loss reported")
        assertTrue(agent !in afterDeaths.positions, "death sweep removed agent from positions")
        assertEquals(0, afterDeaths.bodies[agent]?.hp, "body persists at HP=0 — respawn restores it")

        // Tick 2: agent has set a checkpoint via set_safe_node before dying.
        // Now they call respawn, which materializes at the checkpoint with
        // body restored.
        val gateway = InMemorySafeNodeGateway(checkpoint = mapOf(agent to checkpointId))
        val resolver = GatewayBackedResolver(gateway)
        state = afterDeaths

        val respawnResult = reduceRespawn(
            state,
            WorldCommand.Respawn(agent),
            FixedProfileLookup(profile = AgentProfile(id = agent, maxHp = 50, maxStamina = 50, maxMana = 0)),
            gateway,
            resolver,
            tick = 2L,
        )
        val (afterRespawn, respawnEvent) = assertIs<arrow.core.Either.Right<Pair<WorldState, WorldEvent>>>(respawnResult).value
        val respawned = assertIs<WorldEvent.AgentRespawned>(respawnEvent)
        assertEquals(checkpointId, respawned.at)
        assertEquals(true, respawned.fromCheckpoint)
        assertEquals(checkpointId, afterRespawn.positions[agent])
        val restored = assertNotNull(afterRespawn.bodyOf(agent))
        assertEquals(50, restored.hp)
        assertEquals(50, restored.stamina)
        assertEquals(AgentBody.DEFAULT_MAX_HUNGER, restored.hunger, "gauges fully restored on respawn")
    }

    @Test
    fun `unpositioned dead agent on a subsequent tick is not re-swept (idempotency)`() {
        // After the first tick removes the agent from positions, the next tick's
        // sweep must skip them — otherwise they'd accrue an infinite penalty
        // before they get a chance to respawn.
        val agents = ScriptedRegistry(emptyMap())  // no scripted outcome → would error if called
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to node),
            positions = emptyMap(),  // already removed by a prior tick
            bodies = mapOf(agent to body(hp = 0, hunger = 0)),
            inventories = emptyMap(),
        )

        val (next, events) = processDeaths(state, balance(), agents, tick = 5L)

        assertEquals(state, next)
        assertEquals(emptyList(), events)
    }

    private fun body(hp: Int, hunger: Int) = AgentBody(
        hp = hp, maxHp = 50,
        stamina = 50, maxStamina = 50,
        mana = 0, maxMana = 0,
        hunger = hunger, maxHunger = AgentBody.DEFAULT_MAX_HUNGER,
        thirst = AgentBody.DEFAULT_MAX_THIRST, maxThirst = AgentBody.DEFAULT_MAX_THIRST,
        sleep = AgentBody.DEFAULT_MAX_SLEEP, maxSleep = AgentBody.DEFAULT_MAX_SLEEP,
    )

    private fun balance(
        starvationDamage: Int = 1,
        xpLoss: Int = 25,
    ): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun gatherStaminaCost(item: ItemId): Int = 5
        override fun gatherYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = starvationDamage
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun xpLossOnDeath(): Int = xpLoss
    }

    private class ScriptedRegistry(
        private val outcomes: Map<AgentId, DeathPenaltyOutcome>,
    ) : dev.gvart.genesara.player.AgentRegistry {
        override fun find(id: AgentId): dev.gvart.genesara.player.Agent? = error("not used")
        override fun findByToken(token: String): dev.gvart.genesara.player.Agent? = error("not used")
        override fun listForOwner(owner: dev.gvart.genesara.account.PlayerId): List<dev.gvart.genesara.player.Agent> =
            error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? =
            outcomes[agentId] ?: error("no scripted outcome for $agentId")
    }

    private class InMemorySafeNodeGateway(
        checkpoint: Map<AgentId, NodeId> = emptyMap(),
    ) : dev.gvart.genesara.world.AgentSafeNodeGateway {
        private val store = checkpoint.toMutableMap()
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) { store[agentId] = nodeId }
        override fun find(agentId: AgentId): NodeId? = store[agentId]
        override fun clear(agentId: AgentId) { store.remove(agentId) }
    }

    /**
     * Resolver that defers entirely to the gateway — used here so the test
     * exercises the `find → return` path without depending on AgentRegistry
     * for race resolution.
     */
    private class GatewayBackedResolver(
        private val gateway: dev.gvart.genesara.world.AgentSafeNodeGateway,
    ) : SafeNodeResolver {
        override fun resolveFor(agentId: AgentId): SafeNodeResolution? =
            gateway.find(agentId)?.let { SafeNodeResolution(it, fromCheckpoint = true) }
    }

    private class FixedProfileLookup(private val profile: AgentProfile) : AgentProfileLookup {
        override fun find(id: AgentId): AgentProfile? = if (id == profile.id) profile else null
    }
}
