package dev.gvart.genesara.world.internal.grounditems

import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Exercises [RedisGroundItemStore] against a real Redis. Pins the Lua atomic
 * `take` (only the first racer wins), the JSON codec round-trip for both
 * stackable and equipment drops, and the empty-node read.
 */
@Testcontainers
class RedisGroundItemStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private val nodeA = NodeId(1L)
    private val nodeB = NodeId(2L)

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var store: RedisGroundItemStore
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        store = RedisGroundItemStore(template, mapper)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `stackable deposit + atNode round-trips`() {
        val drop = stackable(item = ItemId("WOOD"), quantity = 50)

        store.deposit(nodeA, drop, droppedAtTick = 100L)

        val view = store.atNode(nodeA).single()
        assertEquals(nodeA, view.nodeId)
        assertEquals(100L, view.droppedAtTick)
        val payload = view.drop as DroppedItemView.Stackable
        assertEquals(drop.dropId, payload.dropId)
        assertEquals(50, payload.quantity)
    }

    @Test
    fun `equipment deposit preserves rarity, durability, creator, and createdAtTick`() {
        val instanceId = UUID.randomUUID()
        val creator = UUID.randomUUID()
        val drop = DroppedItemView.Equipment(
            dropId = UUID.randomUUID(),
            item = ItemId("IRON_SWORD"),
            instanceId = instanceId,
            rarity = Rarity.LEGENDARY,
            durabilityCurrent = 80,
            durabilityMax = 100,
            creatorAgentId = creator,
            createdAtTick = 5L,
        )

        store.deposit(nodeA, drop, droppedAtTick = 10L)

        val payload = store.atNode(nodeA).single().drop as DroppedItemView.Equipment
        assertEquals(instanceId, payload.instanceId)
        assertEquals(Rarity.LEGENDARY, payload.rarity)
        assertEquals(80, payload.durabilityCurrent)
        assertEquals(100, payload.durabilityMax)
        assertEquals(creator, payload.creatorAgentId)
        assertEquals(5L, payload.createdAtTick)
    }

    @Test
    fun `take returns the drop and removes it from Redis`() {
        val drop = stackable(item = ItemId("STONE"), quantity = 3)
        store.deposit(nodeA, drop, droppedAtTick = 1L)

        val taken = assertNotNull(store.take(nodeA, drop.dropId))

        assertEquals(drop.dropId, taken.drop.dropId)
        assertEquals(emptyList(), store.atNode(nodeA), "Redis hash should be empty after take")
    }

    @Test
    fun `take returns null when dropId not on the requested node`() {
        val drop = stackable(item = ItemId("WOOD"), quantity = 1)
        store.deposit(nodeA, drop, droppedAtTick = 1L)

        // Same dropId, wrong node — atomic take returns null.
        assertNull(store.take(nodeB, drop.dropId))
        // And the original drop is untouched on nodeA.
        assertEquals(1, store.atNode(nodeA).size)
    }

    @Test
    fun `concurrent take — second caller receives null`() {
        val drop = stackable(item = ItemId("WOOD"), quantity = 1)
        store.deposit(nodeA, drop, droppedAtTick = 1L)

        val firstWinner = assertNotNull(store.take(nodeA, drop.dropId))
        val secondLoser = store.take(nodeA, drop.dropId)

        assertEquals(drop.dropId, firstWinner.drop.dropId)
        assertNull(secondLoser, "Lua HGET+HDEL atomic — second caller sees nothing")
    }

    @Test
    fun `multiple drops at the same node are all returned by atNode`() {
        val a = stackable(item = ItemId("WOOD"), quantity = 1)
        val b = stackable(item = ItemId("STONE"), quantity = 2)
        store.deposit(nodeA, a, droppedAtTick = 1L)
        store.deposit(nodeA, b, droppedAtTick = 2L)

        val list = store.atNode(nodeA).sortedBy { (it.drop as DroppedItemView.Stackable).item.value }
        assertEquals(2, list.size)
        assertEquals(ItemId("STONE"), (list[0].drop as DroppedItemView.Stackable).item)
        assertEquals(ItemId("WOOD"), (list[1].drop as DroppedItemView.Stackable).item)
    }

    @Test
    fun `atNode returns empty list when no drops at the node`() {
        assertEquals(emptyList(), store.atNode(nodeA))
    }

    private fun stackable(item: ItemId, quantity: Int): DroppedItemView.Stackable =
        DroppedItemView.Stackable(dropId = UUID.randomUUID(), item = item, quantity = quantity)
}
