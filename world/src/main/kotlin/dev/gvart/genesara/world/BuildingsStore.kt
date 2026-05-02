package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Persistent store for [Building] instances. Mirrors the [EquipmentInstanceStore]
 * shape: per-instance UUID PK, transactional reads/writes, no ORM caching.
 *
 * Side-channel writes during the build / progress / completion path — the build
 * reducer calls into this store inside its own transaction (matches how
 * `GatherReducer` decrements via `NodeResourceStore`). The immutable `WorldState`
 * snapshot does not carry buildings; lookups always go through the store.
 */
interface BuildingsStore {

    /**
     * Insert a freshly-placed building (typically at `progressSteps = 1`,
     * `status = UNDER_CONSTRUCTION`). PK uniqueness on `instance_id` rejects
     * a double-insert with the same id; callers generate a fresh UUID per
     * build event.
     */
    fun insert(building: Building)

    /**
     * Single-instance lookup by id. Returns `null` when no row exists. Used by
     * the inspect tool and the chest deposit / withdraw reducers.
     */
    fun findById(id: UUID): Building?

    /**
     * Find an in-progress (UNDER_CONSTRUCTION) building of [type] at [node]
     * built by [agent]. Drives the find-or-create-then-advance branch in the
     * build reducer: an existing row is advanced, otherwise a new one is
     * inserted. Returns `null` when no matching in-progress instance exists.
     *
     * Consequence: an agent can only have ONE in-progress instance of a given
     * type on a given node at a time — a second call to `build CAMPFIRE`
     * advances the first; to start a parallel campfire, finish the first.
     */
    fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building?

    /**
     * Every building (any status) attached to [node], ordered by `instance_id`
     * for stability. Used by the inspect / look_around projections when only
     * one node is needed.
     */
    fun listAtNode(node: NodeId): List<Building>

    /**
     * **Batched** read across [nodes] — one round-trip via `WHERE node_id = ANY(?)`.
     * The look_around projection MUST use this rather than calling [listAtNode]
     * in a per-node loop (see project memory `feedback_read_path_perf.md`).
     * Nodes with no buildings are absent from the result map.
     */
    fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>>

    /**
     * Advance an existing UNDER_CONSTRUCTION instance by one (or more) step.
     * Used for non-final steps only — [newProgress] must be strictly less
     * than the row's `total_steps`. Returns the updated row, or `null` when
     * no row matches [id] or the row is already ACTIVE.
     *
     * Calling this with `newProgress == total_steps` would violate the
     * `(status = ACTIVE) = (progress_steps = total_steps)` schema CHECK; use
     * [complete] for the terminal step instead.
     */
    fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building?

    /**
     * Atomically flip an UNDER_CONSTRUCTION instance to ACTIVE: sets
     * `progress_steps = total_steps` AND `status = 'ACTIVE'` in one UPDATE.
     * Two columns must move together to satisfy the schema CHECK that pins
     * the equivalence between completion and active status. Returns the
     * updated row, or `null` when no row matches [id].
     */
    fun complete(id: UUID, asOfTick: Long): Building?
}
