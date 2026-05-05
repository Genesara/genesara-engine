package dev.gvart.genesara.world

import java.util.UUID

/**
 * Per-node ground-item storage. The death sweep deposits drops here; the
 * `pickup` reducer takes them. Concurrent pickup is resolved by [take]
 * being atomic — only the first caller for a given `(node, dropId)` wins.
 *
 * Redis-only and intentionally ephemeral — a Redis flush or container restart
 * vanishes every in-flight drop, which is acceptable for v1.
 */
interface GroundItemStore {

    /**
     * Persist [drop] at [node], dropped on tick [droppedAtTick]. Idempotent on
     * `drop.dropId`: re-depositing the same drop id is a logic error and the
     * store throws. The death sweep generates a fresh UUID per drop.
     */
    fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long)

    /** Snapshot of every drop currently sitting at [node]. Empty if nothing dropped. */
    fun atNode(node: NodeId): List<GroundItemView>

    /**
     * Atomic take. Returns the drop and removes it from both Redis and Postgres.
     * Returns null when no drop with [dropId] exists at [node] — covers both
     * "wrong node" and "already taken by another agent on the same tick".
     */
    fun take(node: NodeId, dropId: UUID): GroundItemView?
}
