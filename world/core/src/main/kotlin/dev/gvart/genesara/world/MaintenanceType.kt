package dev.gvart.genesara.world

/**
 * Tag matched by `maintain_transport` between a maintenance resource and a
 * transport. A resource declares its [MaintenanceType] via [Item.maintenance];
 * a transport (mount today, vehicles later) declares which type it accepts.
 * The verb rejects when the two don't match — a horse won't eat fuel; a car
 * won't run on hay.
 */
enum class MaintenanceType {
    /** Biological transport (mounts). Maintenance gauge: hunger. */
    ANIMAL,
}
