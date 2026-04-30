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
     * Returns every equipment instance currently held by [agentId], ordered by
     * `instance_id` for a stable response shape. Empty for agents with no gear.
     */
    fun listByAgent(agentId: AgentId): List<EquipmentInstance>

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
