package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Per-instance equipment storage. Distinct from the stackable `agent_inventory`
 * substrate: every row here is one physical item with its own rarity, live
 * durability, and creator signature.
 *
 * Empty at slice land time (no slice yet writes equipment instances). Future
 * slices will populate it from:
 *  - the crafting reducer (creator signature, rolled rarity from skill + Luck),
 *  - loot tables (random rarity from a tier roll, no creator),
 *  - admin tools (seeding starter equipment for tutorial / debug runs).
 */
interface EquipmentInstanceStore {

    /**
     * Insert a freshly-rolled equipment instance. Idempotent on `instance_id`:
     * a re-insert with the same id is rejected by the PK, so callers should
     * generate a fresh UUID per craft / loot event.
     */
    fun insert(instance: EquipmentInstance)

    /**
     * Look up a single instance by id. Returns null when no row exists.
     * Used by equip / unequip flows to validate ownership and read the item id
     * before deciding what slot rules apply.
     */
    fun findById(instanceId: UUID): EquipmentInstance?

    /**
     * Returns every equipment instance currently held by [agentId] (equipped
     * and unequipped), ordered by `instance_id` for a stable response shape.
     * Empty for agents with no gear.
     */
    fun listByAgent(agentId: AgentId): List<EquipmentInstance>

    /**
     * Returns the `(slot → instance)` map for the agent's currently-equipped
     * gear. Slots with no equipped instance are absent from the map (rather
     * than mapped to null), so the caller can iterate cleanly.
     */
    fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance>

    /**
     * Move the instance into [slot]. Atomic: returns the updated row, or null
     * when no row matches `(instance_id, agent_id)` — the [agentId] predicate
     * is part of the WHERE clause so this also rejects an attempt to move
     * someone else's instance, defense-in-depth against a caller that forgets
     * to check ownership upstream.
     *
     * The unique partial index `(agent_id, slot) WHERE slot IS NOT NULL`
     * raises `DataAccessException` (Postgres SQLState `23505`) on a slot
     * collision — callers (i.e. the equip service) should pre-check via
     * [equippedFor] but the index is the authoritative fence against TOCTOU
     * races and should be translated to a `SLOT_OCCUPIED`-equivalent rejection
     * by the calling layer.
     */
    fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance?

    /**
     * Clear the slot for [agentId]: set `equipped_in_slot = NULL` on whatever
     * instance is currently there. Returns the cleared instance, or null if
     * the slot was already empty.
     */
    fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance?

    /**
     * Apply [amount] points of wear to the instance and return the resulting
     * row, or `null` when the row no longer exists (already broken / deleted).
     * The new `durability_current` floors at zero.
     *
     * Does NOT auto-delete on reaching zero — callers decide whether the broken
     * shape is observable (e.g. equipment slots may keep a broken item visible
     * until the agent drops it). [delete] is the explicit removal path.
     */
    fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance?

    /**
     * Remove the instance row. No-op if the row no longer exists. Returns true
     * if a row was deleted, false otherwise.
     */
    fun delete(instanceId: UUID): Boolean
}
