package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
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
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeathSweepTest {

    private val regionId = RegionId(1L)
    private val nodeAId = NodeId(1L)
    private val nodeBId = NodeId(2L)

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
    private val nodeA = Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val nodeB = Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    @Test
    fun `no agents at HP=0 — sweep is a no-op`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 50, atNode = nodeAId)
        val agents = StubRegistry()  // poison: applyDeathPenalty would throw

        val (next, events) = processDeaths(state, balance(), agents, tick = 1)

        assertEquals(state, next)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `single agent at HP=0 — partial bar branch reports xpLost, no de-level`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(
            mapOf(
                agentId to DeathPenaltyOutcome(
                    xpLost = 25,
                    deleveled = false,
                    attributePointLost = null,
                ),
            ),
        )

        val (next, events) = processDeaths(state, balance(), agents, tick = 7)

        assertTrue(agentId !in next.positions, "agent removed from positions on death")
        // Body persists at HP=0 — the respawn reducer restores it.
        assertEquals(0, next.bodyOf(agentId)?.hp)
        assertEquals(1, events.size)
        val died = events.single()
        assertEquals(agentId, died.agent)
        assertEquals(nodeAId, died.at)
        assertEquals(25, died.xpLost)
        assertEquals(false, died.deleveled)
        assertEquals(null, died.attributePointLost)
        assertEquals(7L, died.tick)
        assertEquals(null, died.causedBy, "starvation deaths have no causing command")
    }

    @Test
    fun `empty bar branch reports deleveled=true and attributePointLost=UNSPENT`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(
            mapOf(
                agentId to DeathPenaltyOutcome(
                    xpLost = 0,
                    deleveled = true,
                    attributePointLost = AttributePointLoss.Unspent,
                ),
            ),
        )

        val (_, events) = processDeaths(state, balance(), agents, tick = 1)

        assertEquals("UNSPENT", events.single().attributePointLost)
        assertEquals(true, events.single().deleveled)
    }

    @Test
    fun `empty bar branch with allocated stat loss reports the attribute name`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(
            mapOf(
                agentId to DeathPenaltyOutcome(
                    xpLost = 0,
                    deleveled = true,
                    attributePointLost = AttributePointLoss.Allocated(Attribute.STRENGTH),
                ),
            ),
        )

        val (_, events) = processDeaths(state, balance(), agents, tick = 1)

        assertEquals("STRENGTH", events.single().attributePointLost)
    }

    @Test
    fun `multiple agents dying at the same tick are sorted by agent id`() {
        // Determinism matters for replay logs and event-stream consumers.
        val firstId = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val secondId = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeAId to nodeA, nodeBId to nodeB),
            positions = mapOf(firstId to nodeAId, secondId to nodeBId),
            bodies = mapOf(firstId to bodyAt(0), secondId to bodyAt(0)),
            inventories = emptyMap(),
        )
        val agents = StubRegistry(
            mapOf(
                firstId to DeathPenaltyOutcome(xpLost = 25, deleveled = false, attributePointLost = null),
                secondId to DeathPenaltyOutcome(xpLost = 25, deleveled = false, attributePointLost = null),
            ),
        )

        val (next, events) = processDeaths(state, balance(), agents, tick = 1)

        assertEquals(emptyMap(), next.positions)
        assertEquals(2, events.size)
        assertEquals(listOf(firstId, secondId), events.map { it.agent })
    }

    @Test
    fun `state-corruption agent (registry returns null) is removed from positions but no event fires`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(returnNull = true)

        val (next, events) = processDeaths(state, balance(), agents, tick = 1)

        assertTrue(agentId !in next.positions, "position cleared so the same row doesn't loop forever")
        assertEquals(emptyList(), events, "no AgentDied event for an agent we couldn't penalize")
    }

    @Test
    fun `unpositioned agent at HP=0 is not swept (already dead, awaiting respawn)`() {
        // Idempotency: an agent who already died on a prior tick (and hasn't
        // respawned yet) is unpositioned. The sweep must skip them — otherwise
        // they'd re-trigger death every tick and rack up infinite penalties.
        val agentId = AgentId(UUID.randomUUID())
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeAId to nodeA),
            positions = emptyMap(),  // unpositioned = already dead
            bodies = mapOf(agentId to bodyAt(0)),
            inventories = emptyMap(),
        )
        val agents = StubRegistry()  // poison: must not be called

        val (next, events) = processDeaths(state, balance(), agents, tick = 1)

        assertEquals(state, next)
        assertEquals(emptyList(), events)
    }

    private fun stateWith(agentId: AgentId, hp: Int, atNode: NodeId): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeAId to nodeA, nodeBId to nodeB),
        positions = mapOf(agentId to atNode),
        bodies = mapOf(agentId to bodyAt(hp)),
        inventories = emptyMap(),
    )

    private fun bodyAt(hp: Int) = AgentBody(
        hp = hp, maxHp = 100,
        stamina = 0, maxStamina = 100,
        mana = 0, maxMana = 0,
    )

    private fun balance(xpLoss: Int = 25): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 1
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun xpLossOnDeath(): Int = xpLoss
    }

    /**
     * Stub registry. Default behavior throws to surface accidental calls; pass
     * a [scriptedOutcomes] map (or [returnNull] = true) to script the death
     * sweep's `applyDeathPenalty` interaction.
     */
    private class StubRegistry(
        private val scriptedOutcomes: Map<AgentId, DeathPenaltyOutcome> = emptyMap(),
        private val returnNull: Boolean = false,
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = error("not used")
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")

        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? {
            if (returnNull) return null
            return scriptedOutcomes[agentId]
                ?: error("no scripted outcome for $agentId — test setup mismatch")
        }
    }
}
