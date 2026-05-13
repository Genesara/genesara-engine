package dev.gvart.genesara.world

import dev.gvart.genesara.player.SkillId
import java.util.UUID

/**
 * Side-table store for per-bar construction progress, backed by `node_building_bars`
 * (V23 migration). Bars are written once on the building's first build step and
 * advanced one-at-a-time as the agent calls `build(type, skill)` for each bar.
 *
 * Always called inside the BuildReducer's surrounding transaction; the store does
 * not open transactions of its own beyond the per-statement defaults.
 */
interface BuildingBarsStore {

    /**
     * Bulk-insert the full set of bars for a freshly-placed building. Called once
     * per building, on the same call that inserts the parent `node_buildings` row.
     * All entries should share [BuildingBar.instanceId]; the store does not enforce
     * that invariant.
     */
    fun insertAll(bars: List<BuildingBar>)

    /**
     * All bars for a single building, ordered by `skill_id` ascending for stability.
     * Returns an empty list when no rows exist (the building either hasn't been
     * inserted or has been deleted).
     */
    fun barsByInstance(instanceId: UUID): List<BuildingBar>

    /**
     * **Batched** read across [instanceIds] — one round-trip via `WHERE instance_id = ANY(?)`.
     * The look_around / inspect projections MUST use this rather than calling
     * [barsByInstance] in a per-instance loop. Instances with no rows are absent
     * from the result map.
     */
    fun barsByInstances(instanceIds: Set<UUID>): Map<UUID, List<BuildingBar>>

    /**
     * Atomically advance one bar's `progress_steps` by 1, returning the updated row.
     * Returns `null` when the bar doesn't exist or is already filled (the schema
     * CHECK `progress_steps <= total_steps` blocks past-full writes).
     */
    fun advanceBar(instanceId: UUID, skill: SkillId): BuildingBar?
}
