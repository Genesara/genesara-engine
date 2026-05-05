package dev.gvart.genesara.world.internal.vision

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class VisionRadiusImplTest {

    private val agent = Agent(
        id = AgentId(UUID.randomUUID()),
        owner = PlayerId(UUID.randomUUID()),
        name = "scout",
        classId = AgentClass.SCOUT,
    )
    private val plainsNode = NodeId(1L)
    private val mountainNode = NodeId(2L)

    @Test
    fun `base sight only when no skill and not on mountain`() {
        val helper = helper(base = 3, survivalLevel = 0)
        assertEquals(3, helper.radiusFor(agent, plainsNode))
    }

    @Test
    fun `mountain tile adds plus one`() {
        val helper = helper(base = 3, survivalLevel = 0)
        assertEquals(4, helper.radiusFor(agent, mountainNode))
    }

    @Test
    fun `slotted Survival level 49 grants no bonus`() {
        val helper = helper(base = 3, survivalLevel = 49)
        assertEquals(3, helper.radiusFor(agent, plainsNode))
    }

    @Test
    fun `slotted Survival level 50 grants plus one`() {
        val helper = helper(base = 3, survivalLevel = 50)
        assertEquals(4, helper.radiusFor(agent, plainsNode))
    }

    @Test
    fun `slotted Survival level 99 still grants only plus one`() {
        val helper = helper(base = 3, survivalLevel = 99)
        assertEquals(4, helper.radiusFor(agent, plainsNode))
    }

    @Test
    fun `slotted Survival level 100 grants plus two`() {
        val helper = helper(base = 3, survivalLevel = 100)
        assertEquals(5, helper.radiusFor(agent, plainsNode))
    }

    @Test
    fun `slotted Survival level 150 grants plus three`() {
        val helper = helper(base = 3, survivalLevel = 150)
        assertEquals(6, helper.radiusFor(agent, plainsNode))
    }

    @Test
    fun `mountain plus slotted Survival 150 stack additively`() {
        val helper = helper(base = 3, survivalLevel = 150)
        assertEquals(7, helper.radiusFor(agent, mountainNode))
    }

    @Test
    fun `unknown current node yields no terrain bonus and does not throw`() {
        val helper = helper(base = 3, survivalLevel = 0)
        assertEquals(3, helper.radiusFor(agent, NodeId(999L)))
    }

    @Test
    fun `only the SURVIVAL slot is consulted — other slotted skills are ignored`() {
        val skills = StubSkills(levelsBySkill = mapOf(SkillId("SCOUT") to 150))
        val helper = VisionRadiusImpl(constantBase(3), skills, stubWorld())
        assertEquals(3, helper.radiusFor(agent, plainsNode))
    }

    private fun helper(base: Int, survivalLevel: Int): VisionRadiusImpl {
        val skills = StubSkills(levelsBySkill = mapOf(SkillId("SURVIVAL") to survivalLevel))
        return VisionRadiusImpl(constantBase(base), skills, stubWorld())
    }

    private fun constantBase(base: Int) = object : ClassPropertiesLookup {
        override fun sightRange(classId: AgentClass?): Int = base
    }

    private fun stubWorld() = StubWorld(
        nodes = mapOf(
            plainsNode to Node(plainsNode, RegionId(1L), q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet()),
            mountainNode to Node(mountainNode, RegionId(1L), q = 1, r = 0, terrain = Terrain.MOUNTAIN, adjacency = emptySet()),
        ),
    )

    private class StubSkills(private val levelsBySkill: Map<SkillId, Int>) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)
        override fun slottedSkillLevel(agent: AgentId, skill: SkillId): Int = levelsBySkill[skill] ?: 0
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class StubWorld(private val nodes: Map<NodeId, Node>) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
    }
}
