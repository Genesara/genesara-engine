package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * Per-instance mount store. Single source of truth — mounts are not mirrored
 * into [WorldState]; reducers (tame, mount/dismount, attack-mount, maintenance
 * sweep, release/claim) and the movement reducer mid-mount path read-write
 * through this interface inside the tick transaction.
 *
 * The store owns the full live mount row (HP, hunger, fatigue, position, ride
 * state, ownership). Permadeath: [delete] is the only termination path; no
 * `died_at_tick` column.
 */
interface MountInstanceStore {

    fun insert(mount: Mount)

    fun findById(mountId: MountId): Mount?

    /** Mounts positioned in any of [nodeIds]. Used by `look_around` and the AttackMount validator. */
    fun byNodes(nodeIds: Collection<NodeId>): List<Mount>

    /**
     * Mount currently mounted by [agentId], if any. Hot path for the movement
     * reducer's mounted-branch test. Index-backed.
     */
    fun findByRider(agentId: AgentId): Mount?

    /** Every live mount. Used by the periodic maintenance sweep. */
    fun all(): List<Mount>

    /** Atomically delete the row. Returns true when a row was deleted. */
    fun delete(mountId: MountId): Boolean

    /**
     * Persist all per-tick mutable fields: node_id, hp_current, hunger,
     * fatigue, mounted_by_agent_id. hp_max, hunger_max, fatigue_max, type,
     * tamed_at_tick are anchored at tame and not written by this method.
     *
     * Returns true when a row was updated, false when the target id did not
     * exist (race against a permadeath delete from the same tick). Callers
     * surfaces the race rather than silently dropping the write.
     */
    fun update(mount: Mount): Boolean

    companion object {
        /**
         * In-memory empty store. Default for tests that don't exercise the
         * mounts code path; production wiring overrides with
         * `JooqMountInstanceStore`.
         */
        val NoOp: MountInstanceStore = object : MountInstanceStore {
            override fun insert(mount: Mount) = error("MountInstanceStore.NoOp received an insert — wire JooqMountInstanceStore")
            override fun findById(mountId: MountId): Mount? = null
            override fun byNodes(nodeIds: Collection<NodeId>): List<Mount> = emptyList()
            override fun findByRider(agentId: AgentId): Mount? = null
            override fun all(): List<Mount> = emptyList()
            override fun delete(mountId: MountId): Boolean = false
            override fun update(mount: Mount): Boolean = false
        }
    }
}
