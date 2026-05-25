package dev.gvart.genesara.world.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyMember
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import dev.gvart.genesara.world.internal.worldstate.views.PartyReadView
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class PartyFormationBuffTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val mate = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeA = NodeId(1L)
    private val nodeB = NodeId(2L)
    private val balance = TestBalance(5)
    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.PLAINS, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    private fun core(positions: Map<AgentId, NodeId>): CoreSlice = CoreSlice(
        regions = mapOf(regionId to region),
        nodes = mapOf(
            nodeA to Node(nodeA, regionId, 0, 0, Terrain.FOREST, adjacency = emptySet()),
            nodeB to Node(nodeB, regionId, 1, 0, Terrain.FOREST, adjacency = emptySet()),
        ),
        positions = positions,
    )

    @Test
    fun `solo attacker has no buff`() {
        val mul = formationDamageMultiplier(
            attacker = attacker,
            attackerNode = nodeA,
            partyReadView = PartyReadView.NoOp,
            coreView = core(mapOf(attacker to nodeA)),
            balance = balance,
        )
        assertEquals(1.0, mul)
    }

    @Test
    fun `party-mate in a different node does not trigger the buff`() {
        val party = Party(
            partyId = PartyId(UUID.randomUUID()), leaderId = attacker,
            members = listOf(PartyMember(attacker, 0L), PartyMember(mate, 1L)),
            formedAtTick = 0L,
        )
        val mul = formationDamageMultiplier(
            attacker = attacker,
            attackerNode = nodeA,
            partyReadView = SingletonReadView(party),
            coreView = core(mapOf(attacker to nodeA, mate to nodeB)),
            balance = balance,
        )
        assertEquals(1.0, mul)
    }

    @Test
    fun `co-located party-mate applies the configured buff`() {
        val party = Party(
            partyId = PartyId(UUID.randomUUID()), leaderId = attacker,
            members = listOf(PartyMember(attacker, 0L), PartyMember(mate, 1L)),
            formedAtTick = 0L,
        )
        val mul = formationDamageMultiplier(
            attacker = attacker,
            attackerNode = nodeA,
            partyReadView = SingletonReadView(party),
            coreView = core(mapOf(attacker to nodeA, mate to nodeA)),
            balance = balance,
        )
        assertEquals(1.05, mul)
    }

    @Test
    fun `buff is flat — does not scale with extra co-located members`() {
        val third = AgentId(UUID.randomUUID())
        val party = Party(
            partyId = PartyId(UUID.randomUUID()), leaderId = attacker,
            members = listOf(
                PartyMember(attacker, 0L),
                PartyMember(mate, 1L),
                PartyMember(third, 2L),
            ),
            formedAtTick = 0L,
        )
        val mul = formationDamageMultiplier(
            attacker = attacker,
            attackerNode = nodeA,
            partyReadView = SingletonReadView(party),
            coreView = core(mapOf(attacker to nodeA, mate to nodeA, third to nodeA)),
            balance = balance,
        )
        assertEquals(1.05, mul, "buff is flat per spec — three-co-located still adds 5%, not 10%")
    }

    @Test
    fun `unspawned party-mate does not contribute to the co-located count`() {
        val party = Party(
            partyId = PartyId(UUID.randomUUID()), leaderId = attacker,
            members = listOf(PartyMember(attacker, 0L), PartyMember(mate, 1L)),
            formedAtTick = 0L,
        )
        val mul = formationDamageMultiplier(
            attacker = attacker,
            attackerNode = nodeA,
            partyReadView = SingletonReadView(party),
            coreView = core(mapOf(attacker to nodeA)),
            balance = balance,
        )
        assertEquals(1.0, mul)
    }

    private class SingletonReadView(private val party: Party) : PartyReadView {
        override fun partyOf(agentId: AgentId): Party? =
            if (party.contains(agentId)) party else null
        override fun partyIdOf(agentId: AgentId): PartyId? = partyOf(agentId)?.partyId
        override fun find(partyId: PartyId): Party? = party.takeIf { it.partyId == partyId }
    }

    private class TestBalance(private val bonusPercent: Int) : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 1
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 1
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 1
        override fun gaugeLowThreshold(gauge: Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 1
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 1
        override fun sleepRegenPerOfflineTick(): Int = 1
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun partyFormationDamageBonusPercent(): Int = bonusPercent
    }
}
