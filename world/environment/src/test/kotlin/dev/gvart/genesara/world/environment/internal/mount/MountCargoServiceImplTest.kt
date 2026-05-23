package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.MaintenanceType
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountCargoRejection
import dev.gvart.genesara.world.MountCargoResult
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountDef
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountInventoryStore
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MountCargoServiceImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val nodeA = NodeId(1L)
    private val nodeB = NodeId(2L)
    private val mountId = MountId(UUID.randomUUID())
    private val mountType = MountType("RIDING_HORSE")

    private val wood = ItemId("WOOD")
    private val woodItem = resourceItem(wood, weightPerUnit = 100)
    private val saddleId = ItemId("LEATHER_SADDLE")
    private val helmetId = ItemId("IRON_HELMET")
    private val helmet = equipmentItem(helmetId, weightPerUnit = 2000)
    private val saddle = mountGearItem(saddleId, weightPerUnit = 5000, bonus = 0)
    private val harnessId = ItemId("PACK_HARNESS")
    private val harness = mountGearItem(harnessId, weightPerUnit = 4000, bonus = 30_000, slot = MountSlot.HARNESS)

    private val mountDef = MountDef(
        type = mountType,
        displayName = "Riding Horse",
        tamedFrom = null,
        tameability = 50,
        hpMax = 80,
        hungerMax = 100,
        fatigueMax = 100,
        speedFactor = 1.0,
        carryCapacityGrams = 50_000,
        defense = 4,
        gearSlots = setOf(MountSlot.SADDLE, MountSlot.HARNESS),
        maintenanceType = MaintenanceType.ANIMAL,
    )

    private val catalog: MountCatalog = object : MountCatalog {
        override fun byType(type: MountType): MountDef? = if (type == mountType) mountDef else null
        override fun byTamedFromNpc(npcType: NpcType): MountDef? = null
        override fun all(): Collection<MountDef> = listOf(mountDef)
    }

    private val items = StubItemLookup(
        mapOf(
            wood to woodItem,
            saddleId to saddle,
            harnessId to harness,
            helmetId to helmet,
        ),
    )

    @Test
    fun `storeResource happy path moves stack from agent to mount and decrements inventory`() {
        val agentInv = FakeAgentInventory(mutableMapOf(agent to mutableMapOf(wood to 10)))
        val cargo = FakeMountInventory()
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val instances = FakeInstancesStore()
        val service = service(mounts, cargo, instances, agentInv, world = worldAt(agent, nodeA))

        val result = service.storeResource(agent, mountId, wood, 4)

        assertEquals(MountCargoResult.Stored, result)
        assertEquals(6, agentInv.quantityOf(agent, wood))
        assertEquals(mapOf(wood to 4), cargo.byMount(mountId))
    }

    @Test
    fun `takeResource happy path returns stack to agent and clears cargo to zero`() {
        val agentInv = FakeAgentInventory(mutableMapOf(agent to mutableMapOf()))
        val cargo = FakeMountInventory(mutableMapOf(mountId to mutableMapOf(wood to 3)))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val instances = FakeInstancesStore()
        val service = service(mounts, cargo, instances, agentInv, world = worldAt(agent, nodeA))

        val result = service.takeResource(agent, mountId, wood, 3)

        assertEquals(MountCargoResult.Taken, result)
        assertEquals(3, agentInv.quantityOf(agent, wood))
        assertEquals(emptyMap(), cargo.byMount(mountId))
    }

    @Test
    fun `storeResource rejects when the request would push load over capacity`() {
        val agentInv = FakeAgentInventory(mutableMapOf(agent to mutableMapOf(wood to 1000)))
        val cargo = FakeMountInventory()
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val instances = FakeInstancesStore()
        val service = service(mounts, cargo, instances, agentInv, world = worldAt(agent, nodeA))

        val result = service.storeResource(agent, mountId, wood, 501)

        val rejected = assertIs<MountCargoResult.Rejected>(result)
        assertEquals(MountCargoRejection.OVER_CAPACITY, rejected.reason)
        assertEquals(1000, agentInv.quantityOf(agent, wood))
        assertEquals(emptyMap(), cargo.byMount(mountId))
    }

    @Test
    fun `storeResource includes HARNESS bonus in capacity total`() {
        val harnessInstance = ItemInstance.MountGear(
            instanceId = UUID.randomUUID(),
            agentId = agent,
            itemId = harnessId,
            rarity = Rarity.COMMON,
            durabilityCurrent = 50,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
            equippedOnMount = mountId.value,
            equippedMountSlot = MountSlot.HARNESS,
        )
        val agentInv = FakeAgentInventory(mutableMapOf(agent to mutableMapOf(wood to 1000)))
        val cargo = FakeMountInventory()
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val instances = FakeInstancesStore(harness = mapOf(MountSlot.HARNESS to harnessInstance))
        val service = service(mounts, cargo, instances, agentInv, world = worldAt(agent, nodeA))

        val result = service.storeResource(agent, mountId, wood, 700)

        assertEquals(MountCargoResult.Stored, result, "50 kg base + 30 kg harness = 80 kg cap covers 700 × 100 g = 70 kg")
    }

    @Test
    fun `storeResource rejects when agent is at a different node than the mount`() {
        val agentInv = FakeAgentInventory(mutableMapOf(agent to mutableMapOf(wood to 5)))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val service = service(mounts, FakeMountInventory(), FakeInstancesStore(), agentInv, world = worldAt(agent, nodeB))

        val result = service.storeResource(agent, mountId, wood, 1)

        assertEquals(MountCargoRejection.NOT_SAME_NODE, (result as MountCargoResult.Rejected).reason)
    }

    @Test
    fun `storeResource rejects when the agent does not own the mount`() {
        val agentInv = FakeAgentInventory(mutableMapOf(agent to mutableMapOf(wood to 5)))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount(owner = otherAgent)))
        val service = service(mounts, FakeMountInventory(), FakeInstancesStore(), agentInv, world = worldAt(agent, nodeA))

        val result = service.storeResource(agent, mountId, wood, 1)

        assertEquals(MountCargoRejection.NOT_YOUR_MOUNT, (result as MountCargoResult.Rejected).reason)
    }

    @Test
    fun `storeResource rejects when the agent does not hold enough of the item`() {
        val agentInv = FakeAgentInventory(mutableMapOf(agent to mutableMapOf(wood to 2)))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val service = service(mounts, FakeMountInventory(), FakeInstancesStore(), agentInv, world = worldAt(agent, nodeA))

        val result = service.storeResource(agent, mountId, wood, 5)

        assertEquals(MountCargoRejection.INSUFFICIENT_INVENTORY, (result as MountCargoResult.Rejected).reason)
    }

    @Test
    fun `takeResource rejects when cargo holds less than requested`() {
        val cargo = FakeMountInventory(mutableMapOf(mountId to mutableMapOf(wood to 2)))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val service = service(mounts, cargo, FakeInstancesStore(), FakeAgentInventory(), world = worldAt(agent, nodeA))

        val result = service.takeResource(agent, mountId, wood, 5)

        assertEquals(MountCargoRejection.INSUFFICIENT_CARGO, (result as MountCargoResult.Rejected).reason)
    }

    @Test
    fun `storeInstance happy path stows an unequipped per-instance item`() {
        val instanceId = UUID.randomUUID()
        val equipped = ItemInstance.Equipment(
            instanceId = instanceId,
            agentId = agent,
            itemId = helmetId,
            rarity = Rarity.COMMON,
            durabilityCurrent = 100,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
            equippedInSlot = null,
        )
        val instances = FakeInstancesStore(rows = mutableMapOf(instanceId to equipped))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val service = service(mounts, FakeMountInventory(), instances, FakeAgentInventory(), world = worldAt(agent, nodeA))

        val result = service.storeInstance(agent, mountId, instanceId)

        assertEquals(MountCargoResult.Stored, result)
        assertEquals(mountId, instances.stowedOn[instanceId])
    }

    @Test
    fun `storeInstance rejects an instance currently equipped on the agent`() {
        val instanceId = UUID.randomUUID()
        val equipped = ItemInstance.Equipment(
            instanceId = instanceId,
            agentId = agent,
            itemId = helmetId,
            rarity = Rarity.COMMON,
            durabilityCurrent = 100,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
            equippedInSlot = EquipSlot.HELMET,
        )
        val instances = FakeInstancesStore(rows = mutableMapOf(instanceId to equipped))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val service = service(mounts, FakeMountInventory(), instances, FakeAgentInventory(), world = worldAt(agent, nodeA))

        val result = service.storeInstance(agent, mountId, instanceId)

        assertEquals(MountCargoRejection.INSTANCE_EQUIPPED, (result as MountCargoResult.Rejected).reason)
    }

    @Test
    fun `takeInstance rejects when instance is not stowed on this mount`() {
        val instanceId = UUID.randomUUID()
        val unstowed = ItemInstance.Equipment(
            instanceId = instanceId,
            agentId = agent,
            itemId = helmetId,
            rarity = Rarity.COMMON,
            durabilityCurrent = 100,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 1L,
            equippedInSlot = null,
        )
        val instances = FakeInstancesStore(rows = mutableMapOf(instanceId to unstowed))
        val mounts = FakeMountStore(mapOf(mountId to ownedMount()))
        val service = service(mounts, FakeMountInventory(), instances, FakeAgentInventory(), world = worldAt(agent, nodeA))

        val result = service.takeInstance(agent, mountId, instanceId)

        assertEquals(MountCargoRejection.INSTANCE_NOT_STOWED_HERE, (result as MountCargoResult.Rejected).reason)
    }

    @Test
    fun `storeResource on an unknown mount returns MOUNT_NOT_FOUND`() {
        val service = service(
            FakeMountStore(emptyMap()),
            FakeMountInventory(),
            FakeInstancesStore(),
            FakeAgentInventory(mutableMapOf(agent to mutableMapOf(wood to 1))),
            world = worldAt(agent, nodeA),
        )

        val result = service.storeResource(agent, mountId, wood, 1)

        assertEquals(MountCargoRejection.MOUNT_NOT_FOUND, (result as MountCargoResult.Rejected).reason)
    }

    private fun ownedMount(owner: AgentId = agent): Mount = Mount(
        id = mountId,
        type = mountType,
        ownerAgentId = owner,
        nodeId = nodeA,
        hpCurrent = 80,
        hpMax = 80,
        hunger = 100,
        hungerMax = 100,
        fatigue = 100,
        fatigueMax = 100,
        mountedByAgentId = null,
        tamedAtTick = 100L,
    )

    private fun service(
        mounts: MountInstanceStore,
        cargo: MountInventoryStore,
        instances: AgentItemInstancesStore,
        agentInv: AgentStackableInventoryGateway,
        world: WorldQueryGateway,
    ) = MountCargoServiceImpl(mounts, cargo, instances, agentInv, items, catalog, world)

    private fun worldAt(agent: AgentId, node: NodeId): WorldQueryGateway = object : StubWorldQueryGateway() {
        override fun activePositionOf(agent: AgentId): NodeId? = if (agent == this@MountCargoServiceImplTest.agent) node else null
    }
}

private fun resourceItem(id: ItemId, weightPerUnit: Int): Item = Item(
    id = id,
    displayName = id.value,
    description = "",
    category = ItemCategory.RESOURCE,
    weightPerUnit = weightPerUnit,
    maxStack = 1000,
)

private fun equipmentItem(id: ItemId, weightPerUnit: Int): Item = Item(
    id = id,
    displayName = id.value,
    description = "",
    category = ItemCategory.EQUIPMENT,
    weightPerUnit = weightPerUnit,
    maxStack = 1,
    maxDurability = 100,
    validSlots = setOf(EquipSlot.HELMET),
)

private fun mountGearItem(
    id: ItemId,
    weightPerUnit: Int,
    bonus: Int,
    slot: MountSlot = MountSlot.SADDLE,
): Item = Item(
    id = id,
    displayName = id.value,
    description = "",
    category = ItemCategory.MOUNT_GEAR,
    weightPerUnit = weightPerUnit,
    maxStack = 1,
    maxDurability = 100,
    mountSlots = setOf(slot),
    mountGearBonus = bonus,
)

private class StubItemLookup(private val table: Map<ItemId, Item>) : ItemLookup {
    override fun byId(id: ItemId): Item? = table[id]
    override fun all(): List<Item> = table.values.toList()
}

private class FakeAgentInventory(
    private val rows: MutableMap<AgentId, MutableMap<ItemId, Int>> = mutableMapOf(),
) : AgentStackableInventoryGateway {
    override fun quantityOf(agent: AgentId, item: ItemId): Int = rows[agent]?.get(item) ?: 0
    override fun increment(agent: AgentId, item: ItemId, quantity: Int) {
        val map = rows.getOrPut(agent) { mutableMapOf() }
        map[item] = (map[item] ?: 0) + quantity
    }
    override fun decrement(agent: AgentId, item: ItemId, quantity: Int) {
        val map = rows[agent] ?: error("no inventory for $agent")
        val current = map[item] ?: error("no row for $item")
        val remaining = current - quantity
        if (remaining > 0) map[item] = remaining else map.remove(item)
    }
}

private class FakeMountInventory(
    private val rows: MutableMap<MountId, MutableMap<ItemId, Int>> = mutableMapOf(),
) : MountInventoryStore {
    override fun byMount(mountId: MountId): Map<ItemId, Int> = rows[mountId]?.toMap() ?: emptyMap()
    override fun increment(mountId: MountId, itemId: ItemId, quantity: Int) {
        val map = rows.getOrPut(mountId) { mutableMapOf() }
        map[itemId] = (map[itemId] ?: 0) + quantity
    }
    override fun decrement(mountId: MountId, itemId: ItemId, quantity: Int): Boolean {
        val map = rows[mountId] ?: return false
        val current = map[itemId] ?: return false
        if (current < quantity) return false
        val remaining = current - quantity
        if (remaining > 0) map[itemId] = remaining else map.remove(itemId)
        return true
    }
    override fun deleteAllFor(mountId: MountId) { rows.remove(mountId) }
    override fun byMounts(mountIds: Collection<MountId>): Map<MountId, Map<ItemId, Int>> =
        mountIds.associateWith { byMount(it) }.filterValues { it.isNotEmpty() }
}

private class FakeMountStore(private val table: Map<MountId, Mount>) : MountInstanceStore {
    override fun insert(mount: Mount) = error("not used")
    override fun findById(mountId: MountId): Mount? = table[mountId]
    override fun byNodes(nodeIds: Collection<NodeId>): List<Mount> = table.values.filter { it.nodeId in nodeIds }
    override fun byOwner(agentId: AgentId): List<Mount> = table.values.filter { it.ownerAgentId == agentId }
    override fun findByRider(agentId: AgentId): Mount? = table.values.firstOrNull { it.mountedByAgentId == agentId }
    override fun all(): List<Mount> = table.values.toList()
    override fun delete(mountId: MountId): Boolean = error("not used")
    override fun update(mount: Mount): Boolean = error("not used")
}

private class FakeInstancesStore(
    private val rows: MutableMap<UUID, ItemInstance> = mutableMapOf(),
    private val harness: Map<MountSlot, ItemInstance.MountGear> = emptyMap(),
) : AgentItemInstancesStore {
    val stowedOn: MutableMap<UUID, MountId> = mutableMapOf()

    override fun insert(instance: ItemInstance) { rows[instance.instanceId] = instance }
    override fun findById(instanceId: UUID): ItemInstance? = rows[instanceId]
    override fun listByAgent(agentId: AgentId): List<ItemInstance> = rows.values.filter { it.agentId == agentId }
    override fun delete(instanceId: UUID): Boolean = rows.remove(instanceId) != null
    override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
    override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
    override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
    override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = null
    override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean = false
    override fun stowOnMount(instanceId: UUID, agentId: AgentId, mountId: MountId): ItemInstance? {
        val instance = rows[instanceId] ?: return null
        if (instance.agentId != agentId) return null
        val equipped = when (instance) {
            is ItemInstance.Equipment -> instance.equippedInSlot != null
            is ItemInstance.MountGear -> instance.equippedOnMount != null
            is ItemInstance.Key -> false
        }
        if (equipped) return null
        stowedOn[instanceId] = mountId
        return instance
    }
    override fun unstowFromMount(instanceId: UUID): ItemInstance? {
        stowedOn.remove(instanceId)
        return rows[instanceId]
    }
    override fun byStowedOnMount(mountId: MountId): List<ItemInstance> =
        stowedOn.filterValues { it == mountId }.keys.mapNotNull { rows[it] }
    override fun gearOnMount(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? = harness[slot]
}

private open class StubWorldQueryGateway : WorldQueryGateway {
    override fun locationOf(agent: AgentId): NodeId? = null
    override fun activePositionOf(agent: AgentId): NodeId? = null
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

