package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InspectNpcToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val nodeId = NodeId(1L)
    private val npcId = NpcId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val npc = Npc(
        id = npcId, type = NpcType("GRAY_WOLF"),
        nodeId = nodeId, spawnNodeId = nodeId,
        hpCurrent = 25, hpMax = 30,
        spawnedAtTick = 0L, lastAttackTick = 0L,
    )
    private val def = NpcDef(
        type = NpcType("GRAY_WOLF"), displayName = "Gray Wolf",
        hpMax = 30, damage = 6, damageType = DamageType.PIERCE, range = 1,
        attackIntervalTicks = 4, defense = 1, dodgeChancePercent = 10,
        aggressionProfile = AggressionProfile.TERRITORIAL, territoryRadius = 1,
    )
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `encodes the response id as npc-prefixed wire form`() {
        val tool = InspectNpcTool(world(), registry(perception = 3), activity)

        val response = tool.invoke("npc:${npcId.value}", toolContext)

        assertEquals("npc", response.kind)
        assertEquals("shallow", response.depth)
        val view = assertNotNull(response.npc)
        assertEquals("npc:${npcId.value}", view.id)
        assertEquals("GRAY_WOLF", view.type)
    }

    @Test
    fun `shallow depth omits detailed and expert fields for low Perception`() {
        val tool = InspectNpcTool(world(), registry(perception = 3), activity)

        val response = tool.invoke("npc:${npcId.value}", toolContext)

        val view = assertNotNull(response.npc)
        assertNull(view.aggressionProfile)
        assertNull(view.hpCurrent)
        assertNull(view.damage)
    }

    @Test
    fun `rejects a bare UUID with bad_target_id`() {
        val tool = InspectNpcTool(world(), registry(perception = 3), activity)

        val response = tool.invoke(npcId.value.toString(), toolContext)

        assertEquals("error", response.kind)
        assertEquals("bad_target_id", response.error?.code)
        assertNull(response.npc)
    }

    @Test
    fun `rejects an agent-prefixed UUID with bad_target_id`() {
        val tool = InspectNpcTool(world(), registry(perception = 3), activity)

        val response = tool.invoke("agent:${npcId.value}", toolContext)

        assertEquals("bad_target_id", response.error?.code)
    }

    @Test
    fun `rejects a malformed UUID after the npc prefix with bad_target_id`() {
        val tool = InspectNpcTool(world(), registry(perception = 3), activity)

        val response = tool.invoke("npc:not-a-uuid", toolContext)

        assertEquals("bad_target_id", response.error?.code)
    }

    @Test
    fun `returns not_visible when the NPC is outside the active-set radius`() {
        val emptyWorld = StubWorld(
            location = nodeId,
            visible = setOf(nodeId),
            npcsByNode = emptyMap(),
            def = def,
        )
        val tool = InspectNpcTool(emptyWorld, registry(perception = 3), activity)

        val response = tool.invoke("npc:${npcId.value}", toolContext)

        assertEquals("not_visible", response.error?.code)
    }

    private fun world() = StubWorld(
        location = nodeId,
        visible = setOf(nodeId),
        npcsByNode = mapOf(nodeId to listOf(npc)),
        def = def,
    )

    private fun registry(perception: Int): AgentRegistry {
        val attrs = AgentAttributes.DEFAULT.copy(perception = perception)
        val agent = Agent(id = agentId, owner = PlayerId(UUID.randomUUID()), name = "tester", attributes = attrs)
        return object : AgentRegistry {
            override fun find(id: AgentId): Agent? = agent.takeIf { it.id == id }
            override fun listForOwner(owner: PlayerId): List<Agent> = listOf(agent).filter { it.owner == owner }
        }
    }

    private class StubWorld(
        private val location: NodeId,
        private val visible: Set<NodeId>,
        private val npcsByNode: Map<NodeId, List<Npc>>,
        private val def: NpcDef,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId = location
        override fun activePositionOf(agent: AgentId): NodeId = location
        override fun node(id: NodeId): dev.gvart.genesara.world.Node? = null
        override fun region(id: dev.gvart.genesara.world.RegionId): dev.gvart.genesara.world.Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = visible
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
        override fun npcsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<Npc>> =
            nodeIds.associateWith { npcsByNode[it].orEmpty() }.filterValues { it.isNotEmpty() }
        override fun npcDef(type: NpcType): NpcDef = def
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
