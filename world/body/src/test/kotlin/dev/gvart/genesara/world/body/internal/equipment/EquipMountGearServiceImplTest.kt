package dev.gvart.genesara.world.body.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.EquipMountGearRejection
import dev.gvart.genesara.world.EquipMountGearResult
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.MaintenanceType
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountDef
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.UnequipMountGearResult
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EquipMountGearServiceImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val mountId = MountId(UUID.randomUUID())
    private val node = NodeId(42L)
    private val elsewhere = NodeId(99L)

    private val saddleId = ItemId("LEATHER_SADDLE")
    private val bardingId = ItemId("IRON_BARDING")
    private val swordId = ItemId("RUSTY_SWORD")

    private val saddle = mountGearItem(saddleId, MountSlot.SADDLE)
    private val barding = mountGearItem(bardingId, MountSlot.BARDING)
    private val sword = Item(
        id = swordId,
        displayName = "Rusty Sword",
        description = "",
        category = ItemCategory.EQUIPMENT,
        weightPerUnit = 1000,
        maxStack = 1,
        maxDurability = 100,
    )

    private val mountType = MountType("RIDING_HORSE")
    private val mountDef = MountDef(
        type = mountType,
        displayName = "Riding Horse",
        tamedFrom = null,
        tameability = 50,
        hpMax = 100,
        hungerMax = 100,
        fatigueMax = 100,
        speedFactor = 1.0,
        carryCapacityGrams = 30_000,
        defense = 0,
        gearSlots = setOf(MountSlot.SADDLE, MountSlot.BARDING),
        maintenanceType = MaintenanceType.ANIMAL,
    )

    private val items = StubItemLookup(mapOf(saddleId to saddle, bardingId to barding, swordId to sword))
    private val catalog = StubMountCatalog(mapOf(mountType to mountDef))

    @Test
    fun `equip moves the instance into the mount slot and returns the updated instance`() {
        val gear = mountGear(saddleId)
        val instances = StubInstances(listOf(gear))
        val mounts = StubMounts(listOf(mountAt(node)))
        val world = StubWorld(mapOf(agent to node))
        val service = EquipMountGearServiceImpl(instances, mounts, catalog, items, world)

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.SADDLE)

        val equipped = assertIs<EquipMountGearResult.Equipped>(result)
        assertEquals(MountSlot.SADDLE, equipped.instance.equippedMountSlot)
        assertEquals(mountId.value, equipped.instance.equippedOnMount)
    }

    @Test
    fun `equip rejects when the instance does not exist`() {
        val service = newService()
        val result = service.equipMountGear(agent, UUID.randomUUID(), mountId, MountSlot.SADDLE)
        assertEquals(EquipMountGearRejection.INSTANCE_NOT_FOUND, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the instance is not MOUNT_GEAR`() {
        val instance = ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = agent,
            itemId = swordId,
            rarity = Rarity.COMMON,
            durabilityCurrent = 50,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
        )
        val instances = StubInstances(listOf(instance))
        val service = EquipMountGearServiceImpl(
            instances,
            StubMounts(listOf(mountAt(node))),
            catalog,
            items,
            StubWorld(mapOf(agent to node)),
        )

        val result = service.equipMountGear(agent, instance.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.NOT_MOUNT_GEAR, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the gear belongs to a different agent`() {
        val gear = mountGear(saddleId, gearOwner = otherAgent)
        val service = serviceWith(gear, mountAt(node))

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.NOT_YOUR_INSTANCE, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the mount does not exist`() {
        val gear = mountGear(saddleId)
        val service = EquipMountGearServiceImpl(
            StubInstances(listOf(gear)),
            StubMounts(emptyList()),
            catalog,
            items,
            StubWorld(mapOf(agent to node)),
        )

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.UNKNOWN_MOUNT, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the caller is not at the mount's node`() {
        val gear = mountGear(saddleId)
        val service = EquipMountGearServiceImpl(
            StubInstances(listOf(gear)),
            StubMounts(listOf(mountAt(node))),
            catalog,
            items,
            StubWorld(mapOf(agent to elsewhere)),
        )

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.NOT_SAME_NODE, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the caller has no active position at all`() {
        val gear = mountGear(saddleId)
        val service = EquipMountGearServiceImpl(
            StubInstances(listOf(gear)),
            StubMounts(listOf(mountAt(node))),
            catalog,
            items,
            StubWorld(emptyMap()),
        )

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.NOT_SAME_NODE, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the slot is not in the item's mountSlots`() {
        val gear = mountGear(saddleId)
        val service = serviceWith(gear, mountAt(node))

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.BARDING)

        assertEquals(EquipMountGearRejection.INVALID_SLOT_FOR_ITEM, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the slot is not on the mount`() {
        val harnessId = ItemId("PACK_HARNESS")
        val harness = mountGearItem(harnessId, MountSlot.HARNESS)
        val gear = mountGear(harnessId)
        val service = EquipMountGearServiceImpl(
            StubInstances(listOf(gear)),
            StubMounts(listOf(mountAt(node))),
            catalog,
            StubItemLookup(mapOf(harnessId to harness)),
            StubWorld(mapOf(agent to node)),
        )

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.HARNESS)

        assertEquals(EquipMountGearRejection.SLOT_NOT_ON_MOUNT, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the instance is already equipped to a mount slot`() {
        val gear = mountGear(saddleId).copy(equippedOnMount = mountId.value, equippedMountSlot = MountSlot.SADDLE)
        val service = serviceWith(gear, mountAt(node))

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.ALREADY_EQUIPPED, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when another instance already occupies the same slot`() {
        val occupant = mountGear(saddleId).copy(equippedOnMount = mountId.value, equippedMountSlot = MountSlot.SADDLE)
        val newcomer = mountGear(saddleId)
        val instances = StubInstances(listOf(occupant, newcomer))
        val mounts = StubMounts(listOf(mountAt(node)))
        val world = StubWorld(mapOf(agent to node))
        val service = EquipMountGearServiceImpl(instances, mounts, catalog, items, world)

        val result = service.equipMountGear(agent, newcomer.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.SLOT_OCCUPIED, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `equip translates a unique-index violation from assignToMountSlot to SLOT_OCCUPIED`() {
        val gear = mountGear(saddleId)
        val instances = ThrowingOnAssignStore(listOf(gear), uniqueViolation())
        val service = EquipMountGearServiceImpl(
            instances,
            StubMounts(listOf(mountAt(node))),
            catalog,
            items,
            StubWorld(mapOf(agent to node)),
        )

        val result = service.equipMountGear(agent, gear.instanceId, mountId, MountSlot.SADDLE)

        assertEquals(EquipMountGearRejection.SLOT_OCCUPIED, (result as EquipMountGearResult.Rejected).reason)
    }

    @Test
    fun `unequip clears the slot for the owner`() {
        val gear = mountGear(saddleId).copy(equippedOnMount = mountId.value, equippedMountSlot = MountSlot.SADDLE)
        val service = serviceWith(gear, mountAt(node))

        val result = service.unequipMountGear(agent, mountId, MountSlot.SADDLE)

        val unequipped = assertIs<UnequipMountGearResult.Unequipped>(result)
        assertNull(unequipped.instance.equippedMountSlot)
        assertNull(unequipped.instance.equippedOnMount)
    }

    @Test
    fun `unequip returns SlotEmpty when the slot has no gear`() {
        val service = serviceWith(null, mountAt(node))
        val result = service.unequipMountGear(agent, mountId, MountSlot.SADDLE)
        assertIs<UnequipMountGearResult.SlotEmpty>(result)
    }

    @Test
    fun `equippedOnMount returns the slot map`() {
        val a = mountGear(saddleId).copy(equippedOnMount = mountId.value, equippedMountSlot = MountSlot.SADDLE)
        val b = mountGear(bardingId).copy(equippedOnMount = mountId.value, equippedMountSlot = MountSlot.BARDING)
        val instances = StubInstances(listOf(a, b))
        val service = EquipMountGearServiceImpl(
            instances,
            StubMounts(listOf(mountAt(node))),
            catalog,
            items,
            StubWorld(mapOf(agent to node)),
        )

        val map = service.equippedOnMount(mountId)

        assertEquals(setOf(MountSlot.SADDLE, MountSlot.BARDING), map.keys)
        assertEquals(a.instanceId, assertNotNull(map[MountSlot.SADDLE]).instanceId)
        assertEquals(b.instanceId, assertNotNull(map[MountSlot.BARDING]).instanceId)
    }

    private fun uniqueViolation(): DataIntegrityViolationException =
        DataIntegrityViolationException("unique violation", SQLException("duplicate", "23505"))

    private fun newService(): EquipMountGearServiceImpl =
        EquipMountGearServiceImpl(
            StubInstances(emptyList()),
            StubMounts(listOf(mountAt(node))),
            catalog,
            items,
            StubWorld(mapOf(agent to node)),
        )

    private fun serviceWith(gear: ItemInstance.MountGear?, mount: Mount): EquipMountGearServiceImpl =
        EquipMountGearServiceImpl(
            StubInstances(listOfNotNull(gear)),
            StubMounts(listOf(mount)),
            catalog,
            items,
            StubWorld(mapOf(agent to node)),
        )

    private fun mountGearItem(id: ItemId, slot: MountSlot): Item = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.MOUNT_GEAR,
        weightPerUnit = 5_000,
        maxStack = 1,
        maxDurability = 100,
        mountSlots = setOf(slot),
        mountGearBonus = 1,
    )

    /**
     * `gearOwner` is the agent who owns the gear instance (i.e. carries
     * it in their stash) — the gear's `agentId`. Distinct from any mount
     * concept; mounts have no per-agent ownership.
     */
    private fun mountGear(
        itemId: ItemId,
        gearOwner: AgentId = agent,
    ): ItemInstance.MountGear = ItemInstance.MountGear(
        instanceId = UUID.randomUUID(),
        agentId = gearOwner,
        itemId = itemId,
        rarity = Rarity.COMMON,
        durabilityCurrent = 100,
        durabilityMax = 100,
        creatorAgentId = null,
        createdAtTick = 1L,
    )

    private fun mountAt(node: NodeId): Mount = Mount(
        id = mountId,
        type = mountType,
        nodeId = node,
        hpCurrent = 100,
        hpMax = 100,
        hunger = 100,
        hungerMax = 100,
        fatigue = 100,
        fatigueMax = 100,
        mountedByAgentId = null,
        tamedAtTick = 1L,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubMountCatalog(private val byType: Map<MountType, MountDef>) : MountCatalog {
        override fun byType(type: MountType): MountDef? = byType[type]
        override fun byTamedFromNpc(npcType: dev.gvart.genesara.world.NpcType): MountDef? = null
        override fun all(): Collection<MountDef> = byType.values
    }

    private class StubInstances(initial: List<ItemInstance>) : InMemoryAgentItemInstancesStore() {
        init {
            for (row in initial) seed(row)
        }
    }

    private class ThrowingOnAssignStore(
        rows: List<ItemInstance.MountGear>,
        private val throwOnAssign: DataIntegrityViolationException,
    ) : InMemoryAgentItemInstancesStore() {
        init {
            for (row in rows) seed(row)
        }

        override fun assignToMountSlot(
            instanceId: UUID,
            agentId: AgentId,
            mountId: MountId,
            slot: MountSlot,
        ): ItemInstance.MountGear? {
            throw throwOnAssign
        }
    }

    private class StubMounts(initial: List<Mount>) : MountInstanceStore {
        private val byId: MutableMap<MountId, Mount> = initial.associateBy { it.id }.toMutableMap()
        override fun insert(mount: Mount) { byId[mount.id] = mount }
        override fun findById(mountId: MountId): Mount? = byId[mountId]
        override fun byNodes(nodeIds: Collection<NodeId>): List<Mount> = byId.values.filter { it.nodeId in nodeIds }
        override fun findByRider(agentId: AgentId): Mount? = byId.values.firstOrNull { it.mountedByAgentId == agentId }
        override fun all(): List<Mount> = byId.values.toList()
        override fun delete(mountId: MountId): Boolean = byId.remove(mountId) != null
        override fun update(mount: Mount): Boolean {
            if (mount.id !in byId) return false
            byId[mount.id] = mount
            return true
        }
    }

    private class StubWorld(private val positions: Map<AgentId, NodeId>) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = positions[agent]
        override fun activePositionOf(agent: AgentId): NodeId? = positions[agent]
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }
}
