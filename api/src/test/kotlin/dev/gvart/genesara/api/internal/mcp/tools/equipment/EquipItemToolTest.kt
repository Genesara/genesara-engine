package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipRejection
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.UnequipResult
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

class EquipItemToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `equipped result returns kind=equipped with the slot and instance id`() {
        val instanceId = UUID.randomUUID()
        val service = StubEquipmentService(
            equipResult = EquipResult.Equipped(sampleInstance(instanceId, EquipSlot.MAIN_HAND)),
        )
        val tool = EquipItemTool(service, activity)

        val res = tool.invoke(EquipItemRequest(instanceId.toString(), "MAIN_HAND"), toolContext)

        assertEquals("equipped", res.kind)
        assertEquals("MAIN_HAND", res.slot)
        assertEquals(instanceId.toString(), res.instanceId)
        assertEquals(null, res.reason)
    }

    @Test
    fun `rejection result returns kind=rejected with snake_case reason and detail`() {
        val service = StubEquipmentService(
            equipResult = EquipResult.Rejected(EquipRejection.OFF_HAND_BLOCKED_BY_TWO_HANDED),
        )
        val tool = EquipItemTool(service, activity)

        val res = tool.invoke(EquipItemRequest(UUID.randomUUID().toString(), "OFF_HAND"), toolContext)

        assertEquals("rejected", res.kind)
        assertEquals("off_hand_blocked_by_two_handed", res.reason)
        assertEquals("OFF_HAND", res.slot)
    }

    @Test
    fun `service-supplied detail is passed through verbatim when present`() {
        // Slice C2: rejections like INSUFFICIENT_ATTRIBUTES carry a pre-formatted
        // detail naming the failing requirement. The tool must surface that
        // string rather than overwriting it with its own generic detailFor map.
        val customDetail = "HEAVY_BLADE requires STRENGTH ≥ 12 (you have 5)"
        val service = StubEquipmentService(
            equipResult = EquipResult.Rejected(
                reason = EquipRejection.INSUFFICIENT_ATTRIBUTES,
                detail = customDetail,
            ),
        )
        val tool = EquipItemTool(service, activity)

        val res = tool.invoke(EquipItemRequest(UUID.randomUUID().toString(), "MAIN_HAND"), toolContext)

        assertEquals("rejected", res.kind)
        assertEquals("insufficient_attributes", res.reason)
        assertEquals(customDetail, res.detail)
    }

    @Test
    fun `bad UUID for instanceId is rejected before the service is touched`() {
        val service = StubEquipmentService()
        val tool = EquipItemTool(service, activity)

        val res = tool.invoke(EquipItemRequest("not-a-uuid", "MAIN_HAND"), toolContext)

        assertEquals("rejected", res.kind)
        assertEquals("bad_request", res.reason)
        assertEquals(0, service.equipCalls.size)
    }

    @Test
    fun `unknown slot string is rejected before the service is touched`() {
        val service = StubEquipmentService()
        val tool = EquipItemTool(service, activity)

        val res = tool.invoke(EquipItemRequest(UUID.randomUUID().toString(), "POCKET"), toolContext)

        assertEquals("rejected", res.kind)
        assertEquals("bad_request", res.reason)
        assertEquals(0, service.equipCalls.size)
    }

    @Test
    fun `slot is normalized to uppercase before lookup`() {
        val instanceId = UUID.randomUUID()
        val service = StubEquipmentService(
            equipResult = EquipResult.Equipped(sampleInstance(instanceId, EquipSlot.HELMET)),
        )
        val tool = EquipItemTool(service, activity)

        val res = tool.invoke(EquipItemRequest(instanceId.toString(), "helmet"), toolContext)

        assertEquals("equipped", res.kind)
        assertEquals(EquipSlot.HELMET, service.equipCalls.single().third)
    }

    private fun sampleInstance(id: UUID, slot: EquipSlot) = EquipmentInstance(
        instanceId = id,
        agentId = agent,
        itemId = ItemId("RUSTY_SWORD"),
        rarity = Rarity.COMMON,
        durabilityCurrent = 50,
        durabilityMax = 50,
        creatorAgentId = null,
        createdAtTick = 1L,
        equippedInSlot = slot,
    )

    private class StubEquipmentService(
        private val equipResult: EquipResult = EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND),
        private val unequipResult: UnequipResult = UnequipResult.SlotEmpty,
        private val equippedMap: Map<EquipSlot, EquipmentInstance> = emptyMap(),
    ) : EquipmentService {
        val equipCalls = mutableListOf<Triple<AgentId, UUID, EquipSlot>>()
        val unequipCalls = mutableListOf<Pair<AgentId, EquipSlot>>()

        override fun equip(agentId: AgentId, instanceId: UUID, slot: EquipSlot): EquipResult {
            equipCalls += Triple(agentId, instanceId, slot)
            return equipResult
        }

        override fun unequip(agentId: AgentId, slot: EquipSlot): UnequipResult {
            unequipCalls += agentId to slot
            return unequipResult
        }

        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = equippedMap
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
