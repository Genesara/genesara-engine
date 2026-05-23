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
    ): MountCargoResult = positiveQty(quantity) { withCtx(agentId, mountId) { ctx ->
        val item = items.byId(itemId)
            ?: return@withCtx reject(MountCargoRejection.UNKNOWN_ITEM, "no catalog entry for ${itemId.value}")
        if (item.category != ItemCategory.RESOURCE) {
            return@withCtx reject(MountCargoRejection.UNKNOWN_ITEM, "${itemId.value} is not a stackable RESOURCE")
        }
        val held = agentInventory.quantityOf(agentId, itemId)
        if (held < quantity) {
            return@withCtx reject(
                MountCargoRejection.INSUFFICIENT_INVENTORY,
                "you hold $held ${itemId.value} (asked for $quantity)",
            )
        }
        val added = item.weightPerUnit.toLong() * quantity
        capacityCheck(ctx, added)?.let { return@withCtx it }

        if (!agentInventory.decrement(agentId, itemId, quantity)) {
            return@withCtx reject(
                MountCargoRejection.INSUFFICIENT_INVENTORY,
                "decrement raced — another writer drained your $itemId below $quantity",
            )
        }
        cargo.increment(mountId, itemId, quantity)
        applyLoadDelta(ctx, added)
        MountCargoResult.Stored
    } }

    @Transactional
    override fun takeResource(
        agentId: AgentId,
        mountId: MountId,
        itemId: ItemId,
        quantity: Int,
    ): MountCargoResult = positiveQty(quantity) { withCtx(agentId, mountId) { ctx ->
        val item = items.byId(itemId)
            ?: return@withCtx reject(MountCargoRejection.UNKNOWN_ITEM, "no catalog entry for ${itemId.value}")
        val cargoQty = cargo.byMount(mountId)[itemId] ?: 0
        if (cargoQty < quantity) {
            return@withCtx reject(
                MountCargoRejection.INSUFFICIENT_CARGO,
                "mount carries $cargoQty ${itemId.value} (asked for $quantity)",
            )
        }
        if (!cargo.decrement(mountId, itemId, quantity)) {
            return@withCtx reject(MountCargoRejection.INSUFFICIENT_CARGO, "cargo decrement raced with another writer")
        }
        agentInventory.increment(agentId, itemId, quantity)
        applyLoadDelta(ctx, -(item.weightPerUnit.toLong() * quantity))
        MountCargoResult.Taken
    } }

    @Transactional
    override fun storeInstance(agentId: AgentId, mountId: MountId, instanceId: UUID): MountCargoResult =
        withCtx(agentId, mountId) { ctx ->
            val instance = instances.findById(instanceId)
                ?: return@withCtx reject(MountCargoRejection.INSTANCE_NOT_FOUND, "no instance with id $instanceId")
            if (instance.agentId != agentId) {
                return@withCtx reject(MountCargoRejection.NOT_YOUR_INSTANCE, "instance belongs to a different agent")
            }
            if (instance.isEquipped()) {
                return@withCtx reject(MountCargoRejection.INSTANCE_EQUIPPED, "instance is currently equipped — unequip first")
            }
            val item = items.byId(instance.itemId)
                ?: return@withCtx reject(MountCargoRejection.UNKNOWN_ITEM, "catalog drift: no entry for ${instance.itemId.value}")
            val added = item.weightPerUnit.toLong()
            capacityCheck(ctx, added)?.let { return@withCtx it }

            instances.stowOnMount(instanceId, agentId, mountId)
                ?.let {
                    applyLoadDelta(ctx, added)
                    MountCargoResult.Stored
                }
                ?: reject(MountCargoRejection.INSTANCE_EQUIPPED, "stow raced with an equip — refused")
        }

    @Transactional
    override fun takeInstance(agentId: AgentId, mountId: MountId, instanceId: UUID): MountCargoResult =
        withCtx(agentId, mountId) { ctx ->
            val instance = instances.findById(instanceId)
                ?: return@withCtx reject(MountCargoRejection.INSTANCE_NOT_FOUND, "no instance with id $instanceId")
            if (instance.agentId != agentId) {
                return@withCtx reject(MountCargoRejection.NOT_YOUR_INSTANCE, "instance belongs to a different agent")
            }
            if (!instances.byStowedOnMount(mountId).any { it.instanceId == instanceId }) {
                return@withCtx reject(
                    MountCargoRejection.INSTANCE_NOT_STOWED_HERE,
                    "instance $instanceId is not stowed on mount $mountId",
                )
            }
            val unstowed = instances.unstowFromMount(instanceId)
                ?: return@withCtx reject(MountCargoRejection.INSTANCE_NOT_FOUND, "instance disappeared mid-write")
            val removed = items.byId(unstowed.itemId)?.weightPerUnit?.toLong() ?: 0L
            applyLoadDelta(ctx, -removed)
            MountCargoResult.Taken
        }

    private data class Ctx(val mount: Mount, val mountDef: MountDef)

    private inline fun positiveQty(quantity: Int, block: () -> MountCargoResult): MountCargoResult =
        if (quantity <= 0) reject(MountCargoRejection.INVALID_QUANTITY, "quantity must be positive, was $quantity")
        else block()

    private inline fun withCtx(
        agentId: AgentId,
        mountId: MountId,
        block: (Ctx) -> MountCargoResult,
    ): MountCargoResult {
        val mount = mounts.findById(mountId)
            ?: return reject(MountCargoRejection.MOUNT_NOT_FOUND, "no mount with that id")
        val def = mountCatalog.byType(mount.type)
            ?: return reject(MountCargoRejection.UNKNOWN_MOUNT_TYPE, "no catalog entry for mount type ${mount.type.value}")
        val agentNode = world.activePositionOf(agentId)
        if (agentNode == null || agentNode != mount.nodeId) {
            return reject(MountCargoRejection.NOT_SAME_NODE, "you must be at the mount's node")
        }
        return block(Ctx(mount, def))
    }

    private fun capacityCheck(ctx: Ctx, addedGrams: Long): MountCargoResult.Rejected? {
        val capacity = capacityOf(ctx.mount, ctx.mountDef)
        val currentLoad = ctx.mount.currentLoadGrams
        return if (currentLoad + addedGrams > capacity) {
            reject(MountCargoRejection.OVER_CAPACITY, "load ${currentLoad}g + ${addedGrams}g > cap ${capacity}g")
        } else null
    }

    private fun capacityOf(mount: Mount, def: MountDef): Long =
        def.carryCapacityGrams.toLong() + mount.harnessCargoBonusGrams.toLong()

    private fun applyLoadDelta(ctx: Ctx, deltaGrams: Long) {
        if (deltaGrams == 0L) return
        val refreshed = mounts.findById(ctx.mount.id) ?: return
        val nextLoad = (refreshed.currentLoadGrams + deltaGrams).coerceAtLeast(0L)
        mounts.update(refreshed.copy(currentLoadGrams = nextLoad))
    }

    private fun ItemInstance.isEquipped(): Boolean = when (this) {
        is ItemInstance.Equipment -> equippedInSlot != null
        is ItemInstance.MountGear -> equippedOnMount != null
        is ItemInstance.Key -> false
    }

    private fun reject(reason: MountCargoRejection, detail: String? = null): MountCargoResult.Rejected =
        MountCargoResult.Rejected(reason, detail)
}
