package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentInventoryAdminControllerTest {

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
    private val wood = Item(
        id = ItemId("WOOD"),
        displayName = "Wood",
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
    )

    private lateinit var store: InMemoryAgentItemInstancesStore
    private lateinit var agents: StubRegistry
    private lateinit var items: StubItems
    private lateinit var inventoryAdmin: FakeInventoryAdmin
    private lateinit var equipment: FakeEquipmentService
    private lateinit var audit: CapturingAudit
    private lateinit var world: StubWorld
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        store = InMemoryAgentItemInstancesStore()
        agents = StubRegistry(setOf(targetAgent))
        items = StubItems(mapOf(sword.id to sword, wood.id to wood))
        inventoryAdmin = FakeInventoryAdmin()
        equipment = FakeEquipmentService(store)
        audit = CapturingAudit()
        world = StubWorld()
        mvc = build()
    }

    @Test
    fun `GET inventory returns the stackable view from WorldQueryGateway`() {
        world.inventory = InventoryView(entries = listOf(InventoryEntry(ItemId("WOOD"), 7)))

        mvc.get("/admin/agents/${targetAgent.id}/inventory").andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(1) }
            jsonPath("$.entries[0].itemId") { value("WOOD") }
            jsonPath("$.entries[0].quantity") { value(7) }
        }
    }

    @Test
    fun `POST inventory adds quantity and records audit row`() {
        mvc.post("/admin/agents/${targetAgent.id}/inventory") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"WOOD","delta":5}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.itemId") { value("WOOD") }
            jsonPath("$.quantity") { value(5) }
        }

        assertEquals(5, inventoryAdmin.stacks[ItemId("WOOD")])
        val entry = audit.recorded.single()
        assertEquals("agent.inventory.adjust", entry.action)
        assertEquals("agent", entry.target)
        assertEquals(targetAgent.id.toString(), entry.targetId)
        assertEquals("WOOD", entry.payload["itemId"])
        assertEquals(5, entry.payload["delta"])
    }

    @Test
    fun `POST inventory with negative delta over-removes and returns ProblemDetail without mutating state`() {
        inventoryAdmin.stacks[ItemId("WOOD")] = 2

        mvc.post("/admin/agents/${targetAgent.id}/inventory") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"WOOD","delta":-5}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") {
                value("agent does not have enough WOOD (has 2, asked to remove 5)")
            }
        }

        assertEquals(2, inventoryAdmin.stacks[ItemId("WOOD")])
        assertEquals(0, audit.recorded.size)
    }

    @Test
    fun `POST inventory rejects unknown item with 404`() {
        mvc.post("/admin/agents/${targetAgent.id}/inventory") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"PHANTOM","delta":1}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("item PHANTOM is not in the catalog") }
        }
    }

    @Test
    fun `POST inventory rejects zero delta with 400`() {
        mvc.post("/admin/agents/${targetAgent.id}/inventory") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"WOOD","delta":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("delta must be non-zero") }
        }
    }

    @Test
    fun `DELETE inventory removes whole stack and records audit`() {
        inventoryAdmin.stacks[ItemId("WOOD")] = 9

        mvc.delete("/admin/agents/${targetAgent.id}/inventory/WOOD").andExpect {
            status { isNoContent() }
        }

        assertEquals(null, inventoryAdmin.stacks[ItemId("WOOD")])
        val entry = audit.recorded.single()
        assertEquals("agent.inventory.removeAll", entry.action)
        assertEquals(9, entry.payload["quantityRemoved"])
    }

    @Test
    fun `POST equipment creates instance with catalog defaults — body backwards-compatible`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.itemId") { value("RUSTY_SWORD") }
            jsonPath("$.rarity") { value("COMMON") }
            jsonPath("$.durabilityCurrent") { value(50) }
            jsonPath("$.durabilityMax") { value(50) }
            jsonPath("$.createdAtTick") { value(99) }
        }

        val inserted = store.insertedEquipment.single()
        assertEquals(targetAgent, inserted.agentId)
        assertEquals(sword.id, inserted.itemId)
        assertEquals("agent.equipment.create", audit.recorded.single().action)
    }

    @Test
    fun `POST equipment honors body overrides for rarity, durability, and creator`() {
        val creator = UUID.randomUUID()

        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD","rarity":"RARE","durabilityCurrent":10,"creatorAgentId":"$creator"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.rarity") { value("RARE") }
            jsonPath("$.durabilityCurrent") { value(10) }
            jsonPath("$.creatorAgentId") { value(creator.toString()) }
        }
    }

    @Test
    fun `POST equipment rejects unknown item with 404`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"PHANTOM"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("item PHANTOM is not in the catalog") }
        }
    }

    @Test
    fun `POST equipment rejects non-equipment item with 400`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"WOOD"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("item WOOD is not EQUIPMENT-category") }
        }
    }

    @Test
    fun `POST equipment rejects out-of-range durabilityCurrent with 400`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD","durabilityCurrent":999}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("durabilityCurrent (999) must be in 0..50") }
        }
    }

    @Test
    fun `PATCH equipment updates rarity and durability and records audit`() {
        val instance = seedSwordInstance()

        mvc.patch("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rarity":"EPIC","durabilityCurrent":20,"durabilityMax":80}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.rarity") { value("EPIC") }
            jsonPath("$.durabilityCurrent") { value(20) }
            jsonPath("$.durabilityMax") { value(80) }
        }

        val entry = audit.recorded.single()
        assertEquals("agent.equipment.patch", entry.action)
    }

    @Test
    fun `PATCH equipment rejects durabilityCurrent above durabilityMax`() {
        val instance = seedSwordInstance()

        mvc.patch("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"durabilityCurrent":999}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("durabilityCurrent (999) must be in 0..50") }
        }
    }

    @Test
    fun `PATCH equipment returns 404 when instance belongs to another agent`() {
        val otherAgent = AgentId(UUID.randomUUID())
        agents = StubRegistry(setOf(targetAgent, otherAgent))
        mvc = build()
        val instance = seedSwordInstance(owner = otherAgent)

        mvc.patch("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rarity":"EPIC"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `DELETE equipment removes instance and records audit`() {
        val instance = seedSwordInstance()

        mvc.delete("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}").andExpect {
            status { isNoContent() }
        }

        assertEquals(null, store.findById(instance.instanceId))
        assertEquals("agent.equipment.delete", audit.recorded.single().action)
    }

    @Test
    fun `POST equip equips into requested slot via EquipmentService and records audit`() {
        val instance = seedSwordInstance()

        mvc.post("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}/equip") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"MAIN_HAND"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.equippedInSlot") { value("MAIN_HAND") }
        }

        assertEquals(EquipSlot.MAIN_HAND, equipment.lastEquipCall?.third)
        assertEquals("agent.equipment.equip", audit.recorded.single().action)
    }

    @Test
    fun `POST equip falls back to item's first validSlot when body omits slot`() {
        val instance = seedSwordInstance()

        mvc.post("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}/equip") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            jsonPath("$.equippedInSlot") { value("MAIN_HAND") }
        }
    }

    @Test
    fun `POST equip surfaces SLOT_OCCUPIED rejection as ProblemDetail when slot conflict`() {
        val instance = seedSwordInstance()
        equipment.rejectWith = EquipResult.Rejected(EquipRejection.SLOT_OCCUPIED)

        mvc.post("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}/equip") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"MAIN_HAND"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("SLOT_OCCUPIED") }
        }

        assertEquals(0, audit.recorded.size)
    }

    @Test
    fun `POST equip surfaces OFF_HAND_OCCUPIED when two-handed conflicts with off-hand`() {
        val instance = seedSwordInstance()
        equipment.rejectWith = EquipResult.Rejected(EquipRejection.OFF_HAND_OCCUPIED)

        mvc.post("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}/equip") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"MAIN_HAND"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("OFF_HAND_OCCUPIED") }
        }
    }

    @Test
    fun `DELETE equip unequips currently-equipped instance and records audit`() {
        val instance = seedSwordInstance(equippedSlot = EquipSlot.MAIN_HAND)

        mvc.delete("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}/equip").andExpect {
            status { isOk() }
            jsonPath("$.equippedInSlot") { value(null as Any?) }
        }

        assertEquals("agent.equipment.unequip", audit.recorded.single().action)
    }

    @Test
    fun `DELETE equip returns 400 when instance is not equipped`() {
        val instance = seedSwordInstance()

        mvc.delete("/admin/agents/${targetAgent.id}/equipment/${instance.instanceId}/equip").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("equipment instance ${instance.instanceId} is not equipped") }
        }
    }

    @Test
    fun `mutating endpoints return 404 when agent is not registered`() {
        agents = StubRegistry(emptySet())
        mvc = build()

        mvc.post("/admin/agents/${targetAgent.id}/inventory") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"WOOD","delta":1}"""
        }.andExpect { status { isNotFound() } }
    }

    private fun seedSwordInstance(
        owner: AgentId = targetAgent,
        equippedSlot: EquipSlot? = null,
    ): ItemInstance.Equipment {
        val instance = ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = owner,
            itemId = sword.id,
            rarity = Rarity.COMMON,
            durabilityCurrent = 50,
            durabilityMax = 50,
            creatorAgentId = null,
            createdAtTick = 0L,
            equippedInSlot = equippedSlot,
        )
        store.seed(instance)
        return instance
    }

    private fun build(): MockMvc =
        MockMvcBuilders.standaloneSetup(
            AgentInventoryAdminController(
                items = items,
                instances = store,
                inventoryAdmin = inventoryAdmin,
                world = world,
                agents = agents,
                equipment = equipment,
                tick = FixedTickClock(99L),
                audit = audit,
            ),
        )
            .setControllerAdvice(GlobalExceptionAdvice())
            .setCustomArgumentResolvers(AdminPrincipalResolver(admin))
            .build()

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

    private class FakeInventoryAdmin : AgentInventoryAdminStore {
        val stacks: MutableMap<ItemId, Int> = mutableMapOf()

        override fun adjust(agentId: AgentId, itemId: ItemId, delta: Int): AdjustResult {
            val current = stacks[itemId] ?: 0
            val next = current + delta
            if (next < 0) return AdjustResult.Insufficient(have = current, asked = -delta)
            if (next == 0) stacks.remove(itemId) else stacks[itemId] = next
            return AdjustResult.Adjusted(quantityAfter = next)
        }

        override fun removeAll(agentId: AgentId, itemId: ItemId): Int {
            val prior = stacks[itemId] ?: 0
            stacks.remove(itemId)
            return prior
        }
    }

    private class FakeEquipmentService(
        private val store: InMemoryAgentItemInstancesStore,
    ) : EquipmentService {
        var rejectWith: EquipResult.Rejected? = null
        var lastEquipCall: Triple<AgentId, UUID, EquipSlot>? = null

        override fun equip(agentId: AgentId, instanceId: UUID, slot: EquipSlot): EquipResult {
            lastEquipCall = Triple(agentId, instanceId, slot)
            rejectWith?.let { return it }
            val assigned = store.assignToSlot(instanceId, agentId, slot)
                ?: return EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND)
            return EquipResult.Equipped(assigned)
        }

        override fun unequip(agentId: AgentId, slot: EquipSlot): UnequipResult =
            store.clearSlot(agentId, slot)?.let(UnequipResult::Unequipped) ?: UnequipResult.SlotEmpty

        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
            store.equippedFor(agentId)
    }

    private class CapturingAudit : AdminAuditLog {
        val recorded: MutableList<AdminAuditEntry> = mutableListOf()
        private var seq = 0L

        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long {
            val next = ++seq
            recorded += AdminAuditEntry(
                seq = next,
                adminId = adminId,
                action = action,
                target = target,
                targetId = targetId,
                payload = payload,
                tick = tick,
                occurredAt = Instant.EPOCH,
            )
            return next
        }

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> =
            error("unused in this test")
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
