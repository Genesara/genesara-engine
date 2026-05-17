package dev.gvart.genesara.world.economy.internal.cultivation

import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.events.EconomyEvent
import org.springframework.stereotype.Component

/**
 * Per-tick neglect-death sweep over every planted plot. Runs inside `WorldTickHandler.tickOne`'s
 * `@Transactional` boundary, so `clearPlanting` writes roll back together with the rest of the
 * tick on lease loss. A crop dies when EITHER:
 *   - it is unripe AND the agent's last tend is older than `neglectWindowTicks`, OR
 *   - its catalog entry no longer resolves (a balance YAML edit removed the crop while a row
 *     still references it) — without this branch the plot would be permanently stuck because
 *     `tend` and `harvest` both reject with `UnknownCrop`.
 *
 * Ripe + neglected plots survive: agents can leave a ripe crop unharvested.
 */
@Component
class CropDecaySweep(
    private val plots: AgentPlotsStore,
    private val crops: CropLookup,
) {

    fun sweep(tick: Long): List<EconomyEvent.CropDied> {
        val planted = plots.listPlantedSnapshot()
        if (planted.isEmpty()) return emptyList()

        return planted.mapNotNull { plot ->
            val plant = plot.plant ?: return@mapNotNull null
            val crop = crops.byId(plant.cropId)
            val shouldDie = if (crop == null) {
                true
            } else {
                val ripe = plant.plantedAtTick + crop.ticksToRipe <= tick
                val neglected = plant.lastTendedAtTick + crop.neglectWindowTicks < tick
                !ripe && neglected
            }
            if (!shouldDie) return@mapNotNull null

            plots.clearPlanting(plot.plotId) ?: return@mapNotNull null
            EconomyEvent.CropDied(
                agent = plant.plantedByAgentId,
                at = plot.nodeId,
                plotId = plot.plotId,
                crop = plant.cropId,
                neglectedSinceTick = plant.lastTendedAtTick,
                tick = tick,
            )
        }
    }
}
