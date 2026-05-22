package dev.gvart.genesara.world

/**
 * Catalog row for one mount type. Loaded from `world-definition/mounts.yaml`.
 *
 * Tuning fields that the runtime reads each tick (tameability, speedFactor,
 * defense, carryCapacityGrams) retune live mounts on next tick without
 * rewriting Postgres rows.
 *
 * The *Max fields (hpMax, hungerMax, fatigueMax) are an exception: they seed
 * the corresponding column on the row at tame time and stay anchored there
 * for the life of the mount. A catalog rebalance does NOT retroactively raise
 * a living mount's hp_max / hunger_max / fatigue_max — that would let a
 * silently-rebalanced max push hp_current past its existing ceiling on the
 * very next sweep tick.
 */
data class MountDef(
    val type: MountType,
    val displayName: String,
    /**
     * NPC type a wild creature of this kind appears as. `tame` resolves a target
     * NPC's type against this field across the catalog to find the resulting
     * mount type. Null for transports with no wild source — future engineer-
     * crafted vehicles (cars, planes) leave this null.
     */
    val tamedFrom: NpcType?,
    /**
     * Base success chance (%) at ANIMAL_HANDLING level 0, before scaling/aura/
     * luck contributions. Tuned per species — easy tames sit around 50, harder
     * mounts well below.
     */
    val tameability: Int,
    val hpMax: Int,
    val hungerMax: Int,
    val fatigueMax: Int,
    /**
     * Multiplier applied to mounted-move fatigue cost. Below 1.0 = faster than
     * walking; above 1.0 = a heavier, slower mount. SADDLE gear lowers the
     * effective factor at run time (see equip wiring).
     */
    val speedFactor: Double,
    /**
     * Cargo capacity in grams. HARNESS gear adds on top at run time. Covers
     * both the stackable mount inventory and any per-instance items stowed on
     * the mount.
     */
    val carryCapacityGrams: Int,
    /** Flat mitigation subtracted from incoming raw damage before BARDING gear adds more. */
    val defense: Int,
    /** Subset of [MountSlot] this mount accepts. `tame` rejects nothing on this — gear-equip does. */
    val gearSlots: Set<MountSlot>,
    /**
     * Maintenance tag matched against [Item.maintenance]. v1 mounts are all
     * [MaintenanceType.ANIMAL]; future engine vehicles tag differently.
     */
    val maintenanceType: MaintenanceType,
)
