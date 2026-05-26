package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.loadout.GetLoadoutTool
import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import dev.gvart.genesara.api.testsupport.InMemoryAgentItemInstancesStore
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AdjustResult
import dev.gvart.genesara.world.AgentInventoryAdminStore
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.EquipRejection
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.UnequipResult
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentInventoryAdminControllerLoadoutIntegrationTest {

    private val admin = Admin(AdminId(UUID.randomUUID()), username = "ops")
    private val targetAgent = AgentId(UUID.randomUUID())
    private val sword = Item(
        id = ItemId("RUSTY_SWORD"),
        displayName = "Rusty Sword",
        description = "",
        category = ItemCategory.EQUIPMENT,
        weightPerUnit = 1500,
        maxStack = 1,
        regenerating = false,
        rarity = Rarity.COMMON,
        maxDurability = 50,
        validSlots = setOf(EquipSlot.MAIN_HAND),
    )
    private val clock = FixedClock(Instant.parse("2026-05-26T12:00:00Z"))

    private lateinit var store: InMemoryAgentItemInstancesStore
    private lateinit var equipment: SlotAwareEquipmentService
    private lateinit var world: StubWorld
    private lateinit var items: StubItems
    private lateinit var mvc: MockMvc
    private lateinit var loadout: GetLoadoutTool

    @BeforeEach
    fun setUp() {
        store = InMemoryAgentItemInstancesStore()
        items = StubItems(mapOf(sword.id to sword))
        world = StubWorld()
        equipment = SlotAwareEquipmentService(store, items)

        val activity = AgentActivityRegistry(clock)
        loadout = GetLoadoutTool(world = world, items = items, store = store, activity = activity)

        mvc = MockMvcBuilders.standaloneSetup(
            AgentInventoryAdminController(
                items = items,
                instances = store,
                inventoryAdmin = MemoryInventoryAdmin(),
                world = world,
                agents = StubRegistry(setOf(targetAgent)),
                equipment = equipment,
                tick = FixedTickClock(7L),
                audit = CapturingAudit(),
            ),
        )
            .setControllerAdvice(GlobalExceptionAdvice())
            .setCustomArgumentResolvers(AdminPrincipalResolver(admin))
            .build()

        AgentContextHolder.set(targetAgent)
    }

    @AfterEach
    fun tearDown() {
        AgentContextHolder.clear()
    }

    @Test
    fun `admin seeds weapon, force-equips it, and get_loadout reflects the equipped slot`() {
        val createResponse = mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD"}"""
        }.andReturn().response.contentAsString

        val instanceId = UUID.fromString(extractField(createResponse, "instanceId"))

        mvc.post("/admin/agents/${targetAgent.id}/equipment/$instanceId/equip") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"MAIN_HAND"}"""
        }.andExpect { status { isOk() } }

        val result = loadout.invoke(ToolContext(emptyMap()))

        val mainHand = result.equipment.slots.single { it.slotId == EquipSlot.MAIN_HAND }
        val equipped = assertNotNull(mainHand.instance, "main-hand should be filled")
        assertEquals(instanceId.toString(), equipped.instanceId)
        assertEquals("RUSTY_SWORD", equipped.itemId)
        assertNull(result.equipment.stash.firstOrNull(), "stash should be empty — the sword is equipped")
    }

    private fun extractField(json: String, field: String): String {
        val needle = "\"$field\":\""
        val start = json.indexOf(needle).also { check(it >= 0) { "field $field not found in $json" } } + needle.length
        val end = json.indexOf("\"", start)
        return json.substring(start, end)
    }

    private class SlotAwareEquipmentService(
        private val store: InMemoryAgentItemInstancesStore,
        private val items: ItemLookup,
    ) : EquipmentService {
        override fun equip(agentId: AgentId, instanceId: UUID, slot: EquipSlot): EquipResult {
            val instance = store.findById(instanceId) as? ItemInstance.Equipment
                ?: return EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND)
            if (instance.agentId != agentId) return EquipResult.Rejected(EquipRejection.NOT_YOUR_INSTANCE)
            val item = items.byId(instance.itemId)
                ?: return EquipResult.Rejected(EquipRejection.UNKNOWN_ITEM)
            if (item.category != ItemCategory.EQUIPMENT || item.validSlots.isEmpty()) {
                return EquipResult.Rejected(EquipRejection.NOT_EQUIPMENT)
            }
            if (slot !in item.validSlots) return EquipResult.Rejected(EquipRejection.INVALID_SLOT_FOR_ITEM)
            if (item.twoHanded && slot != EquipSlot.MAIN_HAND) {
                return EquipResult.Rejected(EquipRejection.TWO_HANDED_NOT_MAIN_HAND)
            }
            val equipped = store.equippedFor(agentId)
            if (item.twoHanded && equipped[EquipSlot.OFF_HAND] != null) {
                return EquipResult.Rejected(EquipRejection.OFF_HAND_OCCUPIED)
            }
            if (equipped[slot] != null) return EquipResult.Rejected(EquipRejection.SLOT_OCCUPIED)
            val assigned = store.assignToSlot(instanceId, agentId, slot)
                ?: return EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND)
            return EquipResult.Equipped(assigned)
        }

        override fun unequip(agentId: AgentId, slot: EquipSlot): UnequipResult =
            store.clearSlot(agentId, slot)?.let(UnequipResult::Unequipped) ?: UnequipResult.SlotEmpty

        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
            store.equippedFor(agentId)
    }

    private class StubItems(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubRegistry(private val present: Set<AgentId>) : AgentRegistry {
        override fun find(id: AgentId): Agent? =
            if (id in present) Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "stub") else null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }

    private class FixedClock(private val now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class MemoryInventoryAdmin : AgentInventoryAdminStore {
        private val stacks: MutableMap<ItemId, Int> = mutableMapOf()
        override fun adjust(agentId: AgentId, itemId: ItemId, delta: Int): AdjustResult {
            val current = stacks[itemId] ?: 0
            val next = current + delta
            if (next < 0) return AdjustResult.Insufficient(have = current, asked = -delta)
            if (next == 0) stacks.remove(itemId) else stacks[itemId] = next
            return AdjustResult.Adjusted(next)
        }

        override fun removeAll(agentId: AgentId, itemId: ItemId): Int {
            val prior = stacks[itemId] ?: 0
            stacks.remove(itemId)
            return prior
        }
    }

    private class CapturingAudit : AdminAuditLog {
        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long = 0L

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = emptyList()
    }

    private class AdminPrincipalResolver(private val admin: Admin) : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter): Boolean =
            parameter.parameterType == Admin::class.java

        override fun resolveArgument(
            parameter: MethodParameter,
            mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest,
            binderFactory: WebDataBinderFactory?,
        ): Any = admin
    }

    private class StubWorld : WorldQueryGateway {
        var inventory: InventoryView = InventoryView(emptyList())
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): dev.gvart.genesara.world.Node? = null
        override fun region(id: dev.gvart.genesara.world.RegionId): dev.gvart.genesara.world.Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = inventory
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
        override fun npcsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<Npc>> = emptyMap()
        override fun npcDef(type: NpcType): NpcDef? = null
    }
}
