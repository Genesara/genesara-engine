package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountCargoRejection
import dev.gvart.genesara.world.MountCargoResult
import dev.gvart.genesara.world.MountCargoService
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountDef
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountInventoryStore
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class MountCargoServiceImpl(
    private val mounts: MountInstanceStore,
    private val cargo: MountInventoryStore,
    private val instances: AgentItemInstancesStore,
    private val agentInventory: AgentStackableInventoryGateway,
    private val items: ItemLookup,
    private val mountCatalog: MountCatalog,
    private val world: WorldQueryGateway,
) : MountCargoService {

    @Transactional
    override fun storeResource(
        agentId: AgentId,
        mountId: MountId,
        itemId: ItemId,
        quantity: Int,
    ): MountCargoResult {
        if (quantity <= 0) return reject(MountCargoRejection.INVALID_QUANTITY, "quantity must be positive, was $quantity")
        val ctx = when (val r = resolveAndValidate(agentId, mountId)) {
            is CtxResolve.Ok -> r.ctx
            is CtxResolve.Err -> return r.rejection
        }

        val item = items.byId(itemId)
            ?: return reject(MountCargoRejection.UNKNOWN_ITEM, "no catalog entry for ${itemId.value}")
        if (item.category != ItemCategory.RESOURCE) {
            return reject(MountCargoRejection.UNKNOWN_ITEM, "${itemId.value} is not a stackable RESOURCE")
        }

        val held = agentInventory.quantityOf(agentId, itemId)
        if (held < quantity) {
            return reject(
                MountCargoRejection.INSUFFICIENT_INVENTORY,
                "you hold $held ${itemId.value} (asked for $quantity)",
            )
        }

        val capacity = capacityOf(ctx.mount, ctx.mountDef)
        val currentLoad = currentLoadGrams(mountId)
        val addedGrams = item.weightPerUnit.toLong() * quantity
        if (currentLoad + addedGrams > capacity) return overCapacity(currentLoad, addedGrams, capacity)

        agentInventory.decrement(agentId, itemId, quantity)
        cargo.increment(mountId, itemId, quantity)
        return MountCargoResult.Stored
    }

    @Transactional
    override fun takeResource(
        agentId: AgentId,
        mountId: MountId,
        itemId: ItemId,
        quantity: Int,
    ): MountCargoResult {
        if (quantity <= 0) return reject(MountCargoRejection.INVALID_QUANTITY, "quantity must be positive, was $quantity")
        when (val r = resolveAndValidate(agentId, mountId)) {
            is CtxResolve.Ok -> {}
            is CtxResolve.Err -> return r.rejection
        }
        if (items.byId(itemId) == null) {
            return reject(MountCargoRejection.UNKNOWN_ITEM, "no catalog entry for ${itemId.value}")
        }

        val cargoQty = cargo.byMount(mountId)[itemId] ?: 0
        if (cargoQty < quantity) {
            return reject(
                MountCargoRejection.INSUFFICIENT_CARGO,
                "mount carries $cargoQty ${itemId.value} (asked for $quantity)",
            )
        }
        if (!cargo.decrement(mountId, itemId, quantity)) {
            return reject(MountCargoRejection.INSUFFICIENT_CARGO, "cargo decrement raced with another writer")
        }
        agentInventory.increment(agentId, itemId, quantity)
        return MountCargoResult.Taken
    }

    @Transactional
    override fun storeInstance(agentId: AgentId, mountId: MountId, instanceId: UUID): MountCargoResult {
        val ctx = when (val r = resolveAndValidate(agentId, mountId)) {
            is CtxResolve.Ok -> r.ctx
            is CtxResolve.Err -> return r.rejection
        }

        val instance = instances.findById(instanceId)
            ?: return reject(MountCargoRejection.INSTANCE_NOT_FOUND, "no instance with id $instanceId")
        if (instance.agentId != agentId) {
            return reject(MountCargoRejection.NOT_YOUR_INSTANCE, "instance belongs to a different agent")
        }
        if (instance.isEquipped()) {
            return reject(MountCargoRejection.INSTANCE_EQUIPPED, "instance is currently equipped — unequip first")
        }

        val item = items.byId(instance.itemId)
            ?: return reject(MountCargoRejection.UNKNOWN_ITEM, "catalog drift: no entry for ${instance.itemId.value}")
        val capacity = capacityOf(ctx.mount, ctx.mountDef)
        val currentLoad = currentLoadGrams(mountId)
        val addedGrams = item.weightPerUnit.toLong()
        if (currentLoad + addedGrams > capacity) return overCapacity(currentLoad, addedGrams, capacity)

        return instances.stowOnMount(instanceId, agentId, mountId)
            ?.let { MountCargoResult.Stored }
            ?: reject(MountCargoRejection.INSTANCE_EQUIPPED, "stow raced with an equip — refused")
    }

    @Transactional
    override fun takeInstance(agentId: AgentId, mountId: MountId, instanceId: UUID): MountCargoResult {
        when (val r = resolveAndValidate(agentId, mountId)) {
            is CtxResolve.Ok -> {}
            is CtxResolve.Err -> return r.rejection
        }

        val instance = instances.findById(instanceId)
            ?: return reject(MountCargoRejection.INSTANCE_NOT_FOUND, "no instance with id $instanceId")
        if (instance.agentId != agentId) {
            return reject(MountCargoRejection.NOT_YOUR_INSTANCE, "instance belongs to a different agent")
        }
        if (!instances.byStowedOnMount(mountId).any { it.instanceId == instanceId }) {
            return reject(
                MountCargoRejection.INSTANCE_NOT_STOWED_HERE,
                "instance $instanceId is not stowed on mount $mountId",
            )
        }
        return instances.unstowFromMount(instanceId)
            ?.let { MountCargoResult.Taken }
            ?: reject(MountCargoRejection.INSTANCE_NOT_FOUND, "instance disappeared mid-write")
    }

    private data class Ctx(val mount: Mount, val mountDef: MountDef)

    private sealed interface CtxResolve {
        data class Ok(val ctx: Ctx) : CtxResolve
        data class Err(val rejection: MountCargoResult.Rejected) : CtxResolve
    }

    private fun resolveAndValidate(agentId: AgentId, mountId: MountId): CtxResolve {
        val mount = mounts.findById(mountId)
            ?: return CtxResolve.Err(reject(MountCargoRejection.MOUNT_NOT_FOUND, "no mount with that id"))
        val def = mountCatalog.byType(mount.type)
            ?: return CtxResolve.Err(reject(MountCargoRejection.UNKNOWN_MOUNT_TYPE, "no catalog entry for mount type ${mount.type.value}"))
        if (mount.ownerAgentId != agentId) {
            return CtxResolve.Err(reject(MountCargoRejection.NOT_YOUR_MOUNT, "mount is not owned by you"))
        }
        val agentNode = world.activePositionOf(agentId)
        if (agentNode == null || agentNode != mount.nodeId) {
            return CtxResolve.Err(reject(MountCargoRejection.NOT_SAME_NODE, "you must be at the mount's node"))
        }
        return CtxResolve.Ok(Ctx(mount, def))
    }

    private fun capacityOf(mount: Mount, def: MountDef): Long {
        val harness = instances.gearOnMount(mount.id, MountSlot.HARNESS)
        val bonus = harness?.let { items.byId(it.itemId)?.mountGearBonus } ?: 0
        return def.carryCapacityGrams.toLong() + bonus.toLong()
    }

    private fun currentLoadGrams(mountId: MountId): Long {
        val stackGrams = cargo.byMount(mountId).entries.sumOf { (item, qty) ->
            (items.byId(item)?.weightPerUnit?.toLong() ?: 0L) * qty
        }
        val instanceGrams = instances.byStowedOnMount(mountId).sumOf { row ->
            items.byId(row.itemId)?.weightPerUnit?.toLong() ?: 0L
        }
        return stackGrams + instanceGrams
    }

    private fun ItemInstance.isEquipped(): Boolean = when (this) {
        is ItemInstance.Equipment -> equippedInSlot != null
        is ItemInstance.MountGear -> equippedOnMount != null
        is ItemInstance.Key -> false
    }

    private fun overCapacity(currentLoad: Long, addedGrams: Long, capacity: Long): MountCargoResult.Rejected =
        reject(MountCargoRejection.OVER_CAPACITY, "load ${currentLoad}g + ${addedGrams}g > cap ${capacity}g")

    private fun reject(reason: MountCargoRejection, detail: String? = null): MountCargoResult.Rejected =
        MountCargoResult.Rejected(reason, detail)
}
