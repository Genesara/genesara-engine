package dev.gvart.genesara.world

import java.util.UUID

/**
 * Persistent store for GATE per-instance OPEN/CLOSED state. Backed by
 * `building_gate_states` — one row per ACTIVE gate, inserted on build-complete
 * and cascade-deleted when the parent building row is removed.
 *
 * Non-gate buildings have no row; reads for unknown ids return `null`.
 */
interface BuildingGateStateStore {

    /** Insert a fresh gate-state row, defaulting to CLOSED. Called on GATE build completion. */
    fun insertClosed(gateInstanceId: UUID)

    /** Read whether [gateInstanceId] is OPEN. `null` when no row exists for the id (non-gate or pre-completion). */
    fun isOpen(gateInstanceId: UUID): Boolean?

    /** Flip the gate's state. Returns the new state. Returns `null` when no row exists. */
    fun toggle(gateInstanceId: UUID): Boolean?
}
