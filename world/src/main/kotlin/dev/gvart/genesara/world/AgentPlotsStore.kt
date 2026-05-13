package dev.gvart.genesara.world

import java.util.UUID

/**
 * Persistent store for [AgentPlot] rows. Mirrors [BuildingsStore]: per-instance
 * UUID PK, transactional reads/writes, side-channel writes during the cultivation
 * reducer path. The immutable `WorldState` snapshot does not carry plots.
 */
interface AgentPlotsStore {

    fun insertEmpty(plot: AgentPlot)

    fun findById(plotId: UUID): AgentPlot?

    /** Reserved for the future inspect(BUILDING) FARM_PLOT projection. */
    fun findByBuilding(buildingInstanceId: UUID): AgentPlot?

    /**
     * Batched read across [nodes] — one round-trip via `WHERE node_id = ANY(?)`.
     * Look_around uses this; never call findByBuilding in a loop.
     */
    fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<AgentPlot>>

    /** Atomic empty→planted transition. Guarded on `planted_crop IS NULL`; concurrent plants lose. */
    fun plant(plotId: UUID, crop: PlantedCrop): AgentPlot?

    /** Bump `last_tended_at_tick`. Guarded on `planted_crop IS NOT NULL`. */
    fun tend(plotId: UUID, tick: Long): AgentPlot?

    /** Clear all crop fields. Used by harvest (success) and the neglect-decay sweep. */
    fun clearPlanting(plotId: UUID): AgentPlot?

    /** Snapshot of every plot with something planted. Drives the per-tick decay sweep. */
    fun listPlantedSnapshot(): List<AgentPlot>
}
