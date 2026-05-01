package dev.gvart.genesara.api.internal.rest.admin

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Slice MockMvc test for [EquipmentAdminController]. Standalone setup wires the
 * controller plus [GlobalExceptionAdvice] only, so framework-level path/body
 * binding (UUID parsing, jakarta validation) and our domain exceptions are
 * exercised end-to-end without booting the full Spring context.
 */
class EquipmentAdminControllerTest {

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

    private lateinit var store: StubStore
    private lateinit var agents: StubRegistry
    private lateinit var items: StubItems
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        store = StubStore()
        agents = StubRegistry(setOf(targetAgent))
        items = StubItems(mapOf(sword.id to sword, wood.id to wood))
        mvc = build(items)
    }

    @Test
    fun `seed creates an instance with catalog defaults and returns 201`() {
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

        val inserted = store.inserted.single()
        assertEquals(targetAgent, inserted.agentId)
        assertEquals(sword.id, inserted.itemId)
    }

    @Test
    fun `seed honors body overrides for rarity, durability, and creator`() {
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
    fun `seed rejects malformed agentId with 400`() {
        mvc.post("/admin/agents/not-a-uuid/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `seed returns 404 when the target agent is not registered`() {
        agents = StubRegistry(emptySet())
        mvc = build(items)

        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("agent ${targetAgent.id} is not registered") }
        }
    }

    @Test
    fun `seed returns 400 when itemId is blank`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"   "}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `seed returns 404 when the item is unknown`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"PHANTOM"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("item PHANTOM is not in the catalog") }
        }
    }

    @Test
    fun `seed returns 400 when the item is a stackable resource`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"WOOD"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("item WOOD is not EQUIPMENT-category") }
        }
    }

    @Test
    fun `seed returns 400 when the item has no maxDurability`() {
        // Equipment-category catalog entry that's missing maxDurability — the
        // YAML loader doesn't currently enforce this, so the seed path is the
        // last guard before a malformed instance lands in the DB.
        val brokenItem = sword.copy(id = ItemId("BROKEN_SPEC"), maxDurability = null)
        items = StubItems(mapOf(brokenItem.id to brokenItem))
        mvc = build(items)

        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"BROKEN_SPEC"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `seed rejects an unknown rarity name with 400`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD","rarity":"ULTRA_LEGENDARY"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `seed rejects an out-of-range durabilityCurrent with 400`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD","durabilityCurrent":999}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("durabilityCurrent (999) must be in 0..50") }
        }
    }

    @Test
    fun `seed rejects a malformed creator UUID with 400`() {
        mvc.post("/admin/agents/${targetAgent.id}/equipment") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemId":"RUSTY_SWORD","creatorAgentId":"not-a-uuid"}"""
        }.andExpect { status { isBadRequest() } }
    }

    private fun build(items: ItemLookup): MockMvc =
        MockMvcBuilders.standaloneSetup(EquipmentAdminController(items, store, agents, FixedTickClock(99L)))
            .setControllerAdvice(GlobalExceptionAdvice())
            .build()

    private class StubItems(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubStore : EquipmentInstanceStore {
        val inserted = mutableListOf<EquipmentInstance>()
        override fun insert(instance: EquipmentInstance) { inserted += instance }
        override fun findById(instanceId: UUID): EquipmentInstance? = inserted.firstOrNull { it.instanceId == instanceId }
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = inserted.filter { it.agentId == agentId }
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private class StubRegistry(private val present: Set<AgentId>) : AgentRegistry {
        override fun find(id: AgentId): Agent? =
            if (id in present) Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "stub", apiToken = "t") else null
        override fun findByToken(token: String): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }
}
