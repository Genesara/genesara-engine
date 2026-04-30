package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipRejection
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.UnequipResult
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.util.UUID

@Component
internal class EquipmentServiceImpl(
    private val store: EquipmentInstanceStore,
    private val items: ItemLookup,
) : EquipmentService {

    private val log = LoggerFactory.getLogger(EquipmentServiceImpl::class.java)

    @Transactional
    override fun equip(agentId: AgentId, instanceId: UUID, slot: EquipSlot): EquipResult {
        val instance = store.findById(instanceId)
            ?: return EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND)
        if (instance.agentId != agentId) {
            return EquipResult.Rejected(EquipRejection.NOT_YOUR_INSTANCE)
        }

        val item = items.byId(instance.itemId) ?: run {
            // State corruption: an instance row references an item id that's no
            // longer in the YAML catalog (item was renamed/removed without a
            // data migration). The agent can't recover; an operator needs to
            // see this. Surface as a rejection so the agent gets a structured
            // response, but log loud.
            log.warn(
                "equip: state corruption — instance {} references unknown item id {}",
                instance.instanceId,
                instance.itemId.value,
            )
            return EquipResult.Rejected(EquipRejection.UNKNOWN_ITEM)
        }
        if (item.category != ItemCategory.EQUIPMENT || item.validSlots.isEmpty()) {
            return EquipResult.Rejected(EquipRejection.NOT_EQUIPMENT)
        }
        if (slot !in item.validSlots) {
            return EquipResult.Rejected(EquipRejection.INVALID_SLOT_FOR_ITEM)
        }
        if (item.twoHanded && slot != EquipSlot.MAIN_HAND) {
            // Defensive: a two-handed item should declare validSlots = [MAIN_HAND]
            // in the catalog, so this branch only fires on a malformed catalog
            // entry. We still guard the reducer because the YAML loader doesn't
            // currently reject the inconsistency at startup.
            return EquipResult.Rejected(EquipRejection.TWO_HANDED_NOT_MAIN_HAND)
        }
        if (instance.equippedInSlot != null) {
            return EquipResult.Rejected(EquipRejection.ALREADY_EQUIPPED)
        }

        // Read the live slot map once. Under READ COMMITTED a concurrent
        // unequip / equip from the same agent can shift the map between this
        // read and the assignToSlot below — we accept that staleness for the
        // two-handed and slot-occupancy pre-checks, since the partial unique
        // index on (agent_id, slot) is the authoritative collision fence and
        // is translated to SLOT_OCCUPIED below. Agents are single-threaded in
        // practice so the staleness window is small.
        val equipped = store.equippedFor(agentId)

        if (item.twoHanded && equipped[EquipSlot.OFF_HAND] != null) {
            return EquipResult.Rejected(EquipRejection.OFF_HAND_OCCUPIED)
        }
        if (slot == EquipSlot.OFF_HAND) {
            val mainHandTwoHanded = equipped[EquipSlot.MAIN_HAND]
                ?.let { items.byId(it.itemId)?.twoHanded == true }
                ?: false
            if (mainHandTwoHanded) {
                return EquipResult.Rejected(EquipRejection.OFF_HAND_BLOCKED_BY_TWO_HANDED)
            }
        }
        if (equipped[slot] != null) {
            return EquipResult.Rejected(EquipRejection.SLOT_OCCUPIED)
        }

        // The unique partial index `(agent_id, equipped_in_slot)` is the
        // authoritative fence: even if two equip calls race past the SLOT_OCCUPIED
        // pre-check above, the second one fails the index. We translate the
        // resulting integrity-violation into SLOT_OCCUPIED so the agent sees
        // the same rejection shape as the pre-check would have produced.
        val updated = try {
            store.assignToSlot(instanceId, agentId, slot)
                ?: return EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND)
        } catch (ex: DataIntegrityViolationException) {
            if (ex.isUniqueConstraintViolation()) {
                return EquipResult.Rejected(EquipRejection.SLOT_OCCUPIED)
            }
            throw ex
        }
        return EquipResult.Equipped(updated)
    }

    /**
     * Spring's `DataIntegrityViolationException` wraps the underlying JDBC
     * `SQLException`; Postgres uses SQLState `23505` (`unique_violation`) for
     * unique-index collisions. Walk the cause chain to find that specific
     * state — anything else (CHECK constraint, NOT NULL) should keep
     * propagating since it isn't a slot-collision.
     */
    private fun DataIntegrityViolationException.isUniqueConstraintViolation(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == "23505") return true
            cause = cause.cause
        }
        return false
    }

    @Transactional
    override fun unequip(agentId: AgentId, slot: EquipSlot): UnequipResult {
        val cleared = store.clearSlot(agentId, slot)
            ?: return UnequipResult.SlotEmpty
        return UnequipResult.Unequipped(cleared)
    }

    @Transactional(readOnly = true)
    override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
        store.equippedFor(agentId)
}
