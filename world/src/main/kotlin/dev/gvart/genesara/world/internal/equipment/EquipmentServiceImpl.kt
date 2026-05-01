package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.world.EquipRejection
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.Item
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
    private val agents: AgentRegistry,
    private val skills: AgentSkillsRegistry,
) : EquipmentService {

    private val log = LoggerFactory.getLogger(EquipmentServiceImpl::class.java)

    @Transactional
    override fun equip(agentId: AgentId, instanceId: UUID, slot: EquipSlot): EquipResult {
        val instance = store.findById(instanceId)
            ?: return EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND)
        if (instance.agentId != agentId) {
            return EquipResult.Rejected(EquipRejection.NOT_YOUR_INSTANCE)
        }

        val item = items.byId(instance.itemId)
            ?: return rejectAndLogCatalogMiss(instance.instanceId, instance.itemId.value)

        validateItemSlotCompatibility(item, slot)?.let { return it }
        if (instance.equippedInSlot != null) {
            return EquipResult.Rejected(EquipRejection.ALREADY_EQUIPPED)
        }
        checkAttributeRequirements(agentId, item)?.let { return it }
        checkSkillRequirements(agentId, item)?.let { return it }
        validateLiveSlotMap(agentId, item, slot)?.let { return it }

        return assignToSlotWithRaceTranslation(instanceId, agentId, slot)
    }

    /**
     * Catalog miss after equip-instance lookup means the YAML catalog dropped or renamed
     * the item without a data migration. Surface a structured rejection to the agent and
     * log loudly so an operator notices.
     */
    private fun rejectAndLogCatalogMiss(instanceId: UUID, itemId: String): EquipResult.Rejected {
        log.warn("equip: state corruption — instance {} references unknown item id {}", instanceId, itemId)
        return EquipResult.Rejected(EquipRejection.UNKNOWN_ITEM)
    }

    private fun validateItemSlotCompatibility(item: Item, slot: EquipSlot): EquipResult.Rejected? {
        if (item.category != ItemCategory.EQUIPMENT || item.validSlots.isEmpty()) {
            return EquipResult.Rejected(EquipRejection.NOT_EQUIPMENT)
        }
        if (slot !in item.validSlots) {
            return EquipResult.Rejected(EquipRejection.INVALID_SLOT_FOR_ITEM)
        }
        if (item.twoHanded && slot != EquipSlot.MAIN_HAND) {
            return EquipResult.Rejected(EquipRejection.TWO_HANDED_NOT_MAIN_HAND)
        }
        return null
    }

    /**
     * Reads the slot map once and catches two-handed conflicts the partial unique index
     * cannot express (an off-hand item blocks a two-handed main-hand and vice versa).
     * Tolerates staleness under READ COMMITTED — agents are single-threaded in practice
     * and the unique index `(agent_id, equipped_in_slot)` is the authoritative fence.
     */
    private fun validateLiveSlotMap(agentId: AgentId, item: Item, slot: EquipSlot): EquipResult.Rejected? {
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
        return null
    }

    /**
     * Translates `(agent_id, equipped_in_slot)` unique-index violations — the authoritative
     * race fence — into [EquipRejection.SLOT_OCCUPIED] so a racing equipper sees the same
     * rejection shape the pre-check produces.
     */
    private fun assignToSlotWithRaceTranslation(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipResult =
        try {
            val updated = store.assignToSlot(instanceId, agentId, slot)
                ?: return EquipResult.Rejected(EquipRejection.INSTANCE_NOT_FOUND)
            EquipResult.Equipped(updated)
        } catch (ex: DataIntegrityViolationException) {
            if (ex.isUniqueConstraintViolation()) {
                EquipResult.Rejected(EquipRejection.SLOT_OCCUPIED)
            } else {
                throw ex
            }
        }

    /**
     * Returns a `Rejected` outcome when the agent doesn't meet at least one of [item]'s
     * `requiredAttributes` floors. Failures are reported in [Attribute.ordinal] order so
     * the rejection detail is deterministic regardless of YAML population. The check fires
     * only on equip; agents that drop below a floor later (de-level on death, debuff)
     * keep gear equipped. An absent registry entry is treated as state corruption (errors
     * out) rather than a recoverable rejection — equipment-store presence implies the
     * agent was registered.
     */
    private fun checkAttributeRequirements(agentId: AgentId, item: Item): EquipResult.Rejected? {
        if (item.requiredAttributes.isEmpty()) return null
        val agent = agents.find(agentId)
            ?: error("equip: agent ${agentId.id} owns instances but isn't in the registry — state corruption")
        val attributes = agent.attributes
        for (attribute in Attribute.entries) {
            val required = item.requiredAttributes[attribute] ?: continue
            val current = attribute.valueOn(attributes)
            if (current < required) {
                return EquipResult.Rejected(
                    EquipRejection.INSUFFICIENT_ATTRIBUTES,
                    detail = "${item.id.value} requires ${attribute.name} ≥ $required (you have $current)",
                )
            }
        }
        return null
    }

    /**
     * Returns a `Rejected` outcome when the agent doesn't meet at least one of [item]'s
     * `requiredSkills` floors. An untrained skill (absent from the snapshot) reads as 0.
     */
    private fun checkSkillRequirements(agentId: AgentId, item: Item): EquipResult.Rejected? {
        if (item.requiredSkills.isEmpty()) return null
        val snapshot = skills.snapshot(agentId)
        for ((skillId, required) in item.requiredSkills) {
            val current = snapshot.perSkill[skillId]?.level ?: 0
            if (current < required) {
                return EquipResult.Rejected(
                    EquipRejection.INSUFFICIENT_SKILLS,
                    detail = "${item.id.value} requires ${skillId.value} level ≥ $required (you have $current)",
                )
            }
        }
        return null
    }

    /**
     * Postgres SQLState `23505` is `unique_violation`. Other integrity errors (CHECK,
     * NOT NULL) keep propagating because they aren't slot-collisions.
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
