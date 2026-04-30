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

        // Per-item requirement gates. Skipped entirely when the catalog declares
        // no prerequisites — the common case for v1 — so resources, helmets,
        // and the rusty sword don't trigger the agent / skill reads. The
        // requirement check fires only on equip; an agent that *drops* below
        // a floor later (de-level on death, debuff) keeps gear equipped.
        checkAttributeRequirements(agentId, item)?.let { return it }
        checkSkillRequirements(agentId, item)?.let { return it }

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
     * Returns a `Rejected` outcome when the agent doesn't meet at least one of
     * [item]'s `requiredAttributes` floors, or null when all attributes pass
     * (or the item declares none).
     *
     * Failures are reported in [Attribute.ordinal] order so the
     * `EquipResult.Rejected.detail` string is deterministic regardless of how
     * the YAML map was populated. Equipment-store presence implies the agent
     * was registered at some point — a null `AgentRegistry.find` here is
     * unreachable through normal flow (insertion validates the agent), so the
     * branch errors out as state corruption rather than masquerading as a
     * recoverable rejection.
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
     * Returns a `Rejected` outcome when the agent doesn't meet at least one of
     * [item]'s `requiredSkills` floors. A skill the agent has never trained
     * (absent from the snapshot) reads as level 0.
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
