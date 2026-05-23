package dev.gvart.genesara.world

/**
 * Per-instance store for the stackable cargo riding on a mount — `mount_inventory`
 * rows. Per-instance items stowed on a mount use [AgentItemInstancesStore]
 * (`stowed_in_mount_id`) instead; this store handles RESOURCE-shaped stacks only.
 *
 * Cargo capacity (`mountDef.carryCapacityGrams` + HARNESS bonus) spans both
 * stores — the [MountCargoService] is responsible for the cross-store weight
 * totalling, not this interface.
 */
interface MountInventoryStore {

    /** Stackable cargo on [mountId] keyed by item id. Empty map when the mount carries no stacks. */
    fun byMount(mountId: MountId): Map<ItemId, Int>

    /**
     * Add [quantity] of [itemId] to the mount's cargo, upserting the row.
     * [quantity] must be positive; callers validate it against the cargo
     * capacity before calling.
     */
    fun increment(mountId: MountId, itemId: ItemId, quantity: Int)

    /**
     * Remove [quantity] of [itemId] from the mount's cargo. Returns false (no
     * write) when the row holds fewer than [quantity] — the caller surfaces
     * the rejection. Removes the row entirely when the resulting quantity is
     * zero.
     */
    fun decrement(mountId: MountId, itemId: ItemId, quantity: Int): Boolean

    /**
     * Delete every cargo row for [mountId]. Used by stage-D mount death
     * cleanup. (The `ON DELETE CASCADE` on `mount_inventory.mount_id` covers
     * the same path when the row itself is deleted; this method exists for
     * cargo-drop sites that need to read rows before destroying them.)
     */
    fun deleteAllFor(mountId: MountId)

    /**
     * Batched read for `look_around` / `inspect`: cargo per mount keyed
     * by mount id, then by item id. Mounts with no cargo are absent from the
     * returned map. Empty input returns an empty map without touching the DB.
     */
    fun byMounts(mountIds: Collection<MountId>): Map<MountId, Map<ItemId, Int>>

    companion object {
        /**
         * In-memory empty store. Default for tests that don't exercise the
         * mount-cargo code path; production wiring overrides with
         * `JooqMountInventoryStore`.
         */
        val NoOp: MountInventoryStore = object : MountInventoryStore {
            override fun byMount(mountId: MountId): Map<ItemId, Int> = emptyMap()
            override fun increment(mountId: MountId, itemId: ItemId, quantity: Int) =
                error("MountInventoryStore.NoOp received an increment — wire JooqMountInventoryStore")
            override fun decrement(mountId: MountId, itemId: ItemId, quantity: Int): Boolean = false
            override fun deleteAllFor(mountId: MountId) = Unit
            override fun byMounts(mountIds: Collection<MountId>): Map<MountId, Map<ItemId, Int>> = emptyMap()
        }
    }
}
