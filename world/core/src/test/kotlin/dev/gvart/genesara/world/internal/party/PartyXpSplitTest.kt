package dev.gvart.genesara.world.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyMember
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import dev.gvart.genesara.world.internal.worldstate.views.PartyReadView
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class PartyXpSplitTest {

    private val killer = AgentId(UUID.randomUUID())
    private val nearby = AgentId(UUID.randomUUID())
    private val faraway = AgentId(UUID.randomUUID())
    private val unspawned = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val killerNode = NodeId(1L)
    private val adjacentNode = NodeId(2L)
    private val farNode = NodeId(99L)
    private val region = Region(
        id = regionId, worldId = dev.gvart.genesara.world.WorldId(1L), sphereIndex = 0,
        biome = dev.gvart.genesara.world.Biome.PLAINS,
        climate = dev.gvart.genesara.world.Climate.OCEANIC,
        centroid = dev.gvart.genesara.world.Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private fun coreSlice(positions: Map<AgentId, NodeId>): CoreSlice = CoreSlice(
        regions = mapOf(regionId to region),
        nodes = mapOf(
            killerNode to Node(killerNode, regionId, 0, 0, Terrain.FOREST, adjacency = setOf(adjacentNode)),
            adjacentNode to Node(adjacentNode, regionId, 1, 0, Terrain.FOREST, adjacency = setOf(killerNode)),
            farNode to Node(farNode, regionId, 99, 99, Terrain.FOREST, adjacency = emptySet()),
        ),
        positions = positions,
    )

    @Test
    fun `solo killer collapses to single recipient`() {
        val members = eligibleKillSplitMembers(
            killer = killer,
            killerNode = killerNode,
            radius = 5,
            partyReadView = NoOpReadView,
            coreView = coreSlice(mapOf(killer to killerNode)),
        )

        assertEquals(listOf(killer), members)
    }

    @Test
    fun `co-located party-mate joins the split`() {
        val partyId = PartyId(UUID.randomUUID())
        val party = Party(
            partyId = partyId, leaderId = killer,
            members = listOf(PartyMember(killer, 0L), PartyMember(nearby, 1L)),
            formedAtTick = 0L,
        )
        val view = SingletonReadView(party)

        val members = eligibleKillSplitMembers(
            killer = killer,
            killerNode = killerNode,
            radius = 1,
            partyReadView = view,
            coreView = coreSlice(mapOf(killer to killerNode, nearby to killerNode)),
        )

        assertEquals(setOf(killer, nearby), members.toSet())
    }

    @Test
    fun `member outside the radius is excluded`() {
        val party = Party(
            partyId = PartyId(UUID.randomUUID()), leaderId = killer,
            members = listOf(PartyMember(killer, 0L), PartyMember(faraway, 1L)),
            formedAtTick = 0L,
        )

        val members = eligibleKillSplitMembers(
            killer = killer,
            killerNode = killerNode,
            radius = 5,
            partyReadView = SingletonReadView(party),
            coreView = coreSlice(mapOf(killer to killerNode, faraway to farNode)),
        )

        assertEquals(listOf(killer), members)
    }

    @Test
    fun `unspawned party-mate is excluded even if their last node would have been in range`() {
        val party = Party(
            partyId = PartyId(UUID.randomUUID()), leaderId = killer,
            members = listOf(PartyMember(killer, 0L), PartyMember(unspawned, 1L)),
            formedAtTick = 0L,
        )

        val members = eligibleKillSplitMembers(
            killer = killer,
            killerNode = killerNode,
            radius = 5,
            partyReadView = SingletonReadView(party),
            coreView = coreSlice(mapOf(killer to killerNode)),
        )

        assertEquals(listOf(killer), members)
    }

    @Test
    fun `adjacent-node party-mate is included when radius is at least 1`() {
        val party = Party(
            partyId = PartyId(UUID.randomUUID()), leaderId = killer,
            members = listOf(PartyMember(killer, 0L), PartyMember(nearby, 1L)),
            formedAtTick = 0L,
        )

        val members = eligibleKillSplitMembers(
            killer = killer,
            killerNode = killerNode,
            radius = 1,
            partyReadView = SingletonReadView(party),
            coreView = coreSlice(mapOf(killer to killerNode, nearby to adjacentNode)),
        )

        assertEquals(setOf(killer, nearby), members.toSet())
    }

    private object NoOpReadView : PartyReadView {
        override fun partyOf(agentId: AgentId): Party? = null
        override fun partyIdOf(agentId: AgentId): PartyId? = null
        override fun find(partyId: PartyId): Party? = null
    }

    private class SingletonReadView(private val party: Party) : PartyReadView {
        override fun partyOf(agentId: AgentId): Party? =
            if (party.contains(agentId)) party else null
        override fun partyIdOf(agentId: AgentId): PartyId? = partyOf(agentId)?.partyId
        override fun find(partyId: PartyId): Party? = party.takeIf { it.partyId == partyId }
    }
}
