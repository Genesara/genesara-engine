package dev.gvart.genesara.api.internal.rest.admin

import dev.gvart.genesara.account.PlayerId
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
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    private val items = StubItems(mapOf(sword.id to sword, wood.id to wood))

    @Test
    fun `seed creates an instance with catalog defaults and returns 201`() {
        val store = StubStore()
        val agents = StubRegistry(setOf(targetAgent))
        val controller = controller(store = store, agents = agents)

        val resp = controller.seed(
            agentId = targetAgent.id.toString(),
            req = SeedEquipmentRequest(itemId = "RUSTY_SWORD"),
        )

        assertEquals(HttpStatus.CREATED, HttpStatus.valueOf(resp.statusCode.value()))
        val body = assertNotNull(resp.body)
        assertEquals("RUSTY_SWORD", body.itemId)
        assertEquals("COMMON", body.rarity)
        assertEquals(50, body.durabilityCurrent)
        assertEquals(50, body.durabilityMax)
        assertEquals(99L, body.createdAtTick)
        assertEquals(null, body.creatorAgentId)

        val inserted = store.inserted.single()
        assertEquals(targetAgent, inserted.agentId)
        assertEquals(sword.id, inserted.itemId)
    }

    @Test
    fun `seed honors body overrides for rarity, durability, and creator`() {
        val store = StubStore()
        val controller = controller(store = store)
        val creator = UUID.randomUUID()

        val resp = controller.seed(
            agentId = targetAgent.id.toString(),
            req = SeedEquipmentRequest(
                itemId = "RUSTY_SWORD",
                rarity = "RARE",
                durabilityCurrent = 10,
                creatorAgentId = creator.toString(),
            ),
        )

        val body = assertNotNull(resp.body)
        assertEquals("RARE", body.rarity)
        assertEquals(10, body.durabilityCurrent)
        assertEquals(creator.toString(), body.creatorAgentId)
    }

    @Test
    fun `seed rejects malformed agentId with 400`() {
        val resp = controller().seed("not-a-uuid", SeedEquipmentRequest(itemId = "RUSTY_SWORD"))
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(resp.statusCode.value()))
        assertNotNull(resp.body?.error)
    }

    @Test
    fun `seed returns 404 when the target agent is not registered`() {
        val controller = controller(agents = StubRegistry(emptySet()))

        val resp = controller.seed(targetAgent.id.toString(), SeedEquipmentRequest(itemId = "RUSTY_SWORD"))

        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.valueOf(resp.statusCode.value()))
    }

    @Test
    fun `seed returns 400 when itemId is missing`() {
        val resp = controller().seed(targetAgent.id.toString(), SeedEquipmentRequest(itemId = null))
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(resp.statusCode.value()))
    }

    @Test
    fun `seed returns 404 when the item is unknown`() {
        val resp = controller().seed(
            targetAgent.id.toString(),
            SeedEquipmentRequest(itemId = "PHANTOM"),
        )
        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.valueOf(resp.statusCode.value()))
    }

    @Test
    fun `seed returns 400 when the item is a stackable resource`() {
        val resp = controller().seed(targetAgent.id.toString(), SeedEquipmentRequest(itemId = "WOOD"))
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(resp.statusCode.value()))
        assertTrue(resp.body?.error?.contains("EQUIPMENT") == true)
    }

    @Test
    fun `seed returns 400 when the item has no maxDurability`() {
        // Equipment-category catalog entry that's missing maxDurability — the
        // YAML loader doesn't currently enforce this, so the seed path is the
        // last guard before a malformed instance lands in the DB.
        val brokenItem = sword.copy(id = ItemId("BROKEN_SPEC"), maxDurability = null)
        val items = StubItems(mapOf(brokenItem.id to brokenItem))
        val controller = controller(items = items)

        val resp = controller.seed(targetAgent.id.toString(), SeedEquipmentRequest(itemId = "BROKEN_SPEC"))

        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(resp.statusCode.value()))
    }

    @Test
    fun `seed rejects an unknown rarity name with 400`() {
        val resp = controller().seed(
            targetAgent.id.toString(),
            SeedEquipmentRequest(itemId = "RUSTY_SWORD", rarity = "ULTRA_LEGENDARY"),
        )
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(resp.statusCode.value()))
    }

    @Test
    fun `seed rejects an out-of-range durabilityCurrent with 400`() {
        val resp = controller().seed(
            targetAgent.id.toString(),
            SeedEquipmentRequest(itemId = "RUSTY_SWORD", durabilityCurrent = 999),
        )
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(resp.statusCode.value()))
    }

    @Test
    fun `seed rejects a malformed creator UUID with 400`() {
        val resp = controller().seed(
            targetAgent.id.toString(),
            SeedEquipmentRequest(itemId = "RUSTY_SWORD", creatorAgentId = "not-a-uuid"),
        )
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(resp.statusCode.value()))
    }

    private fun controller(
        items: ItemLookup = this.items,
        store: StubStore = StubStore(),
        agents: StubRegistry = StubRegistry(setOf(targetAgent)),
        tick: Long = 99L,
    ) = EquipmentAdminController(items, store, agents, FixedTickClock(tick))

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
