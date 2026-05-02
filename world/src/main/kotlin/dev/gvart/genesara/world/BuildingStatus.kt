package dev.gvart.genesara.world

/**
 * Lifecycle stage of a [Building]. Buildings are inserted at
 * [UNDER_CONSTRUCTION] and flip to [ACTIVE] on the build step that pushes
 * `progressSteps` to `totalSteps`. Behavioral effects (chest deposits,
 * road discount, well water source, bridge traversability, shelter safe-node)
 * gate on [ACTIVE] only — half-built structures are inert.
 */
enum class BuildingStatus {
    UNDER_CONSTRUCTION,
    ACTIVE,
}
