package dev.gvart.genesara.world.body.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipMountGearRejection
import dev.gvart.genesara.world.EquipMountGearResult
import dev.gvart.genesara.world.EquipMountGearService
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.UnequipMountGearResult
import dev.gvart.genesara.world.WorldQueryGateway
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.util.UUID

@Component
internal class EquipMountGearServiceImpl(
    private val instances: AgentItemInstancesStore,
    private val mounts: MountInstanceStore,
    private val mountCatalog: MountCatalog,
    private val items: ItemLookup,
    private val world: WorldQueryGateway,
) : EquipMountGearService {

    private val log = LoggerFactory.getLogger(EquipMountGearServiceImpl::class.java)

    @Transactional
    override fun equipMountGear(
        agentId: AgentId,
        instanceId: UUID,
        mountId: MountId,
        slot: MountSlot,
    ): EquipMountGearResult {
        val instance = instances.findById(instanceId)
            ?: return EquipMountGearResult.Rejected(EquipMountGearRejection.INSTANCE_NOT_FOUND)
        val gear = instance as? ItemInstance.MountGear
            ?: return EquipMountGearResult.Rejected(EquipMountGearRejection.NOT_MOUNT_GEAR)
        if (gear.agentId != agentId) {
            return EquipMountGearResult.Rejected(EquipMountGearRejection.NOT_YOUR_INSTANCE)
        }

        val mount = mounts.findById(mountId)
            ?: return EquipMountGearResult.Rejected(EquipMountGearRejection.UNKNOWN_MOUNT)
        val agentNode = world.activePositionOf(agentId)
        if (agentNode != mount.nodeId) {
            return EquipMountGearResult.Rejected(EquipMountGearRejection.NOT_SAME_NODE)
        }

        val item = items.byId(gear.itemId) ?: return rejectAndLogCatalogMiss(gear.instanceId, gear.itemId.value)
        if (item.category != ItemCategory.MOUNT_GEAR || slot !in item.mountSlots) {
            return EquipMountGearResult.Rejected(EquipMountGearRejection.INVALID_SLOT_FOR_ITEM)
        }
        val mountDef = mountCatalog.byType(mount.type)
            ?: error("equipMountGear: mount ${mount.id.value} references unknown type ${mount.type.value}")
        if (slot !in mountDef.gearSlots) {
            return EquipMountGearResult.Rejected(EquipMountGearRejection.SLOT_NOT_ON_MOUNT)
        }

        if (gear.equippedOnMount != null || gear.equippedMountSlot != null) {
            return EquipMountGearResult.Rejected(EquipMountGearRejection.ALREADY_EQUIPPED)
        }
        val occupied = instances.byEquippedOnMount(mountId).any { it.equippedMountSlot == slot }
        if (occupied) {
            return EquipMountGearResult.Rejected(EquipMountGearRejection.SLOT_OCCUPIED)
        }

        return assignWithRaceTranslation(instanceId, agentId, mountId, slot, item.mountGearBonus)
    }

    private fun assignWithRaceTranslation(
        instanceId: UUID,
        agentId: AgentId,
        mountId: MountId,
        slot: MountSlot,
        gearBonus: Int,
    ): EquipMountGearResult =
        try {
            val updated = instances.assignToMountSlot(instanceId, agentId, mountId, slot)
                ?: return EquipMountGearResult.Rejected(EquipMountGearRejection.INSTANCE_NOT_FOUND)
            applyBonusDelta(mountId, slot, gearBonus)
            EquipMountGearResult.Equipped(updated)
        } catch (ex: DataIntegrityViolationException) {
            if (ex.isUniqueConstraintViolation()) {
                EquipMountGearResult.Rejected(EquipMountGearRejection.SLOT_OCCUPIED)
            } else {
                throw ex
            }
        }

    private fun applyBonusDelta(mountId: MountId, slot: MountSlot, delta: Int) {
        if (delta == 0) return
        val current = mounts.findById(mountId) ?: return
        val next = when (slot) {
            MountSlot.SADDLE -> current.copy(saddleSpeedBonus = (current.saddleSpeedBonus + delta).coerceAtLeast(0))
            MountSlot.HARNESS -> current.copy(harnessCargoBonusGrams = (current.harnessCargoBonusGrams + delta).coerceAtLeast(0))
            MountSlot.BARDING -> return
        }
        mounts.update(next)
    }

    private fun rejectAndLogCatalogMiss(instanceId: UUID, itemId: String): EquipMountGearResult.Rejected {
        log.warn("equipMountGear: state corruption — instance {} references unknown item id {}", instanceId, itemId)
        return EquipMountGearResult.Rejected(EquipMountGearRejection.UNKNOWN_ITEM)
    }

    @Transactional
    override fun unequipMountGear(
        agentId: AgentId,
        mountId: MountId,
        slot: MountSlot,
    ): UnequipMountGearResult {
        mounts.findById(mountId) ?: return UnequipMountGearResult.SlotEmpty
        val cleared = instances.clearMountSlot(mountId, slot) ?: return UnequipMountGearResult.SlotEmpty
        val bonus = items.byId(cleared.itemId)?.mountGearBonus ?: 0
        applyBonusDelta(mountId, slot, -bonus)
        return UnequipMountGearResult.Unequipped(cleared)
    }

    @Transactional(readOnly = true)
    override fun equippedOnMount(mountId: MountId): Map<MountSlot, ItemInstance.MountGear> =
        instances.byEquippedOnMount(mountId)
            .filter { it.equippedMountSlot != null }
            .associateBy { it.equippedMountSlot!! }

    private fun DataIntegrityViolationException.isUniqueConstraintViolation(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == "23505") return true
            cause = cause.cause
        }
        return false
    }
}
