package dev.gvart.genesara.world.environment.internal.npc

import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.LootEntry
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcsStore
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.AdminNpcGatewayError
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.RarityRoller
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher

class AdminNpcGatewayImplTest {

    private val nodeAId = NodeId(11L)
    private val nodeBId = NodeId(12L)
    private val wolfType = NpcType("GRAY_WOLF")
    private val woodId = ItemId("WOOD")

    private val wolfDef = NpcDef(
        type = wolfType,
        displayName = "Gray Wolf",
        hpMax = 30,
        damage = 6,
        damageType = DamageType.PIERCE,
        range = 1,
        attackIntervalTicks = 4,
        defense = 1,
        dodgeChancePercent = 0,
        aggressionProfile = AggressionProfile.HOSTILE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = 1,
    )

    private val woodItem = Item(
        id = woodId,
        displayName = "Wood",
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
    )

    private lateinit var store: InMemoryNpcsStore
    private lateinit var catalog: StubCatalog
    private lateinit var groundItems: RecordingGroundItemStore
    private lateinit var publisher: CapturingPublisher
    private lateinit var gateway: AdminNpcGatewayImpl

    @BeforeEach
    fun setup() {
        store = InMemoryNpcsStore()
        catalog = StubCatalog(mapOf(wolfType to wolfDef))
        groundItems = RecordingGroundItemStore()
        publisher = CapturingPublisher()
        gateway = AdminNpcGatewayImpl(
            store = store,
            catalog = catalog,
            lootRoll = lootRollWithSingleWoodDrop(),
            publisher = publisher,
        )
    }

    @Test
    fun `spawn inserts row and emits NpcSpawned with catalog hpMax`() {
        val npc = gateway.spawn(nodeAId, wolfType, hp = null, tick = 100L)

        assertEquals(30, npc.hpCurrent)
        assertEquals(30, npc.hpMax)
        assertEquals(nodeAId, npc.spawnNodeId)
        assertEquals(npc, store.byId[npc.id])
        val spawned = publisher.events.filterIsInstance<EnvironmentEvent.NpcSpawned>().single()
        assertEquals(npc.id, spawned.npc)
        assertEquals(nodeAId, spawned.at)
        assertNull(spawned.causedBy)
    }

    @Test
    fun `spawn honors explicit hp override`() {
        val npc = gateway.spawn(nodeAId, wolfType, hp = 15, tick = 100L)
        assertEquals(15, npc.hpCurrent)
        assertEquals(30, npc.hpMax)
    }

    @Test
    fun `spawn rejects unknown type`() {
        assertFailsWith<AdminNpcGatewayError.UnknownType> {
            gateway.spawn(nodeAId, NpcType("PHANTOM"), hp = null, tick = 100L)
        }
        assertTrue(publisher.events.isEmpty())
        assertTrue(store.byId.isEmpty())
    }

    @Test
    fun `spawn rejects hp greater than catalog hpMax`() {
        assertFailsWith<AdminNpcGatewayError.InvalidHp> {
            gateway.spawn(nodeAId, wolfType, hp = 999, tick = 100L)
        }
    }

    @Test
    fun `update preserves immutable fields and writes to store`() {
        val seeded = seedNpc()

        val updated = gateway.update(seeded.id, hp = 5, nodeId = nodeBId, tick = 200L)

        assertEquals(5, updated.hpCurrent)
        assertEquals(nodeBId, updated.nodeId)
        assertEquals(seeded.spawnNodeId, updated.spawnNodeId)
        assertEquals(seeded.type, updated.type)
        assertEquals(seeded.hpMax, updated.hpMax)
        assertEquals(updated, store.byId[seeded.id])
    }

    @Test
    fun `update with null fields leaves them untouched`() {
        val seeded = seedNpc(hpCurrent = 20)

        val updated = gateway.update(seeded.id, hp = null, nodeId = null, tick = 200L)

        assertEquals(20, updated.hpCurrent)
        assertEquals(nodeAId, updated.nodeId)
    }

    @Test
    fun `update rejects unknown npc`() {
        assertFailsWith<AdminNpcGatewayError.NotFound> {
            gateway.update(NpcId(UUID.randomUUID()), hp = 5, nodeId = null, tick = 200L)
        }
    }

    @Test
    fun `kill silent deletes row without emitting events or running loot`() {
        val seeded = seedNpc()

        val outcome = gateway.kill(seeded.id, silent = true, tick = 300L)

        assertEquals(seeded, outcome.npc)
        assertTrue(outcome.drops.isEmpty())
        assertNull(store.byId[seeded.id])
        assertTrue(publisher.events.isEmpty())
        assertTrue(groundItems.deposits.isEmpty())
    }

    @Test
    fun `kill non-silent emits NpcDied and per-drop ItemDroppedOnGround`() {
        val seeded = seedNpc()

        val outcome = gateway.kill(seeded.id, silent = false, tick = 300L)

        assertEquals(1, outcome.drops.size)
        assertNull(store.byId[seeded.id])
        assertEquals(1, groundItems.deposits.size)

        val died = publisher.events.filterIsInstance<EnvironmentEvent.NpcDied>().single()
        assertEquals(seeded.id, died.npc)
        assertNull(died.killedBy)
        assertEquals(1, died.drops.size)

        val dropEvent = publisher.events.filterIsInstance<EconomyEvent.ItemDroppedOnGround>().single()
        assertNull(dropEvent.byAgent)
        assertEquals(seeded.nodeId, dropEvent.at)
    }

    @Test
    fun `kill rejects unknown npc`() {
        assertFailsWith<AdminNpcGatewayError.NotFound> {
            gateway.kill(NpcId(UUID.randomUUID()), silent = false, tick = 300L)
        }
    }

    private fun seedNpc(hpCurrent: Int = 30): Npc {
        val npc = Npc(
            id = NpcId(UUID.randomUUID()),
            type = wolfType,
            nodeId = nodeAId,
            spawnNodeId = nodeAId,
            hpCurrent = hpCurrent,
            hpMax = 30,
            spawnedAtTick = 0L,
            lastAttackTick = 0L,
        )
        store.insert(npc)
        return npc
    }

    private fun lootRollWithSingleWoodDrop(): LootRoll =
        LootRoll(
            lootTables = object : LootTableCatalog {
                override fun byMob(mob: NpcType): List<LootEntry> = listOf(
                    LootEntry(item = woodId, dropChance = 1.0, quantityMin = 1, quantityMax = 1),
                )
                override fun allMobs(): Set<NpcType> = setOf(wolfType)
            },
            items = object : ItemLookup {
                override fun byId(id: ItemId): Item? = if (id == woodId) woodItem else null
                override fun all(): List<Item> = listOf(woodItem)
            },
            groundItems = groundItems,
            rarityRoller = FixedRarityRoller,
        )

    private class InMemoryNpcsStore : NpcsStore {
        val byId = mutableMapOf<NpcId, Npc>()
        override fun insert(npc: Npc) { byId[npc.id] = npc }
        override fun findById(npcId: NpcId): Npc? = byId[npcId]
        override fun byNodes(nodeIds: Collection<NodeId>): List<Npc> =
            byId.values.filter { it.nodeId in nodeIds }
        override fun delete(npcId: NpcId): Boolean = byId.remove(npcId) != null
        override fun countAtNode(nodeId: NodeId): Int = byId.values.count { it.nodeId == nodeId }
        override fun update(npc: Npc) { byId[npc.id] = npc }
    }

    private class StubCatalog(private val byType: Map<NpcType, NpcDef>) : NpcCatalog {
        override fun byType(type: NpcType): NpcDef? = byType[type]
        override fun all(): Collection<NpcDef> = byType.values
        override fun byBiome(biome: Biome): List<NpcDef> = emptyList()
    }

    private class RecordingGroundItemStore : GroundItemStore {
        val deposits = mutableListOf<Pair<NodeId, DroppedItemView>>()
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) {
            deposits += node to drop
        }
        override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
    }

    private object FixedRarityRoller : RarityRoller() {
        override fun roll(skillLevel: Int, luck: Int): Rarity = Rarity.COMMON
    }

    private class CapturingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<WorldEvent>()
        override fun publishEvent(event: Any) {
            if (event is WorldEvent) events += event
        }
        override fun publishEvent(event: ApplicationEvent) { /* unused */ }
    }
}
