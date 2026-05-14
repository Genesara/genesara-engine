package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Persistent store for all per-instance carried items — today [ItemInstance.Equipment]
 * and [ItemInstance.Key]. Backed by `world.agent_item_instances`, discriminated by
 * the `category` column. Future per-instance categories (signed crafted items,
 * charged scrolls, named tools) land here without new tables or stores.
 *
 * Generic operations (`insert`, `findById`, `listByAgent`, `delete`) accept and
 * return the sealed [ItemInstance] supertype. Category-specific reads narrow the
 * return type to the corresponding subtype where the SQL predicate already
 * filters by category.
 */
interface AgentItemInstancesStore {

    /**
     * Insert a freshly-rolled instance. Idempotent on `instance_id`: a re-insert
     * with the same id is rejected by the PK. The concrete subtype determines
     * which category-specific columns are populated.
     */
    fun insert(instance: ItemInstance)

    /** Single-instance lookup by id. Returns `null` when no row exists. */
    fun findById(instanceId: UUID): ItemInstance?

    /**
     * Every instance currently held by [agentId], any category, ordered by
     * `(created_at_tick, instance_id)` for a stable response shape.
     */
    fun listByAgent(agentId: AgentId): List<ItemInstance>

    /** Remove the instance row. No-op when the row no longer exists. */
    fun delete(instanceId: UUID): Boolean

    /**
     * Equipment currently occupying each slot for [agentId]. Slots with no
     * equipped instance are absent from the map (rather than mapped to null),
     * so the caller can iterate cleanly.
     */
    fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment>

    /**
     * Batched form of [equippedFor] for per-tick consumers (passive sweep,
     * combat broadcast). Issues one query for all [agents] rather than N
     * single-agent calls. Agents with no equipped gear are absent from the
     * returned map.
     */
    fun equippedForAll(agents: Set<AgentId>): Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>> =
        agents.associateWith { equippedFor(it) }.filterValues { it.isNotEmpty() }

    /**
     * Move an EQUIPMENT instance into [slot]. Atomic: returns the updated row,
     * or null when no EQUIPMENT row matches `(instance_id, agent_id)`. The
     * unique partial index `(agent_id, equipped_in_slot) WHERE equipped_in_slot
     * IS NOT NULL` raises `DataAccessException` (Postgres SQLState `23505`) on a
     * slot collision — callers should pre-check via [equippedFor] but the index
     * is the authoritative fence against TOCTOU races and should be translated
     * to a `SLOT_OCCUPIED`-equivalent rejection by the calling layer.
     */
    fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment?

    /**
     * Clear the slot for [agentId]: set `equipped_in_slot = NULL` on whatever
     * EQUIPMENT instance is currently there. Returns the cleared instance, or
     * null if the slot was already empty.
     */
    fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment?

    /**
     * Apply [amount] points of wear to the EQUIPMENT instance and return the
     * resulting row, or `null` when the row no longer exists OR is not an
     * EQUIPMENT row (no-op, never throws — durability is meaningless for KEY
     * and future non-equipment categories). The new `durability_current` floors
     * at zero.
     *
     * Does NOT auto-delete on reaching zero — callers decide whether the broken
     * shape is observable. [delete] is the explicit removal path.
     */
    fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment?

    /**
     * Does [agent] hold any KEY instance bound to [gateInstanceId]? Hot-path
     * predicate for the gate-toggle / passage check.
     */
    fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean
}
