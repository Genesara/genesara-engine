package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * A cultivation plot bound 1:1 to a [Building] of type [BuildingType.FARM_PLOT].
 * The row is created on FARM_PLOT build completion and removed only when its
 * building is demolished (`ON DELETE CASCADE`). Plots are unowned — any agent
 * at the node can plant, tend, and harvest.
 */
data class AgentPlot(
    val plotId: UUID,
    val buildingInstanceId: UUID,
    val nodeId: NodeId,
    val plant: PlantedCrop?,
) {
    val isEmpty: Boolean get() = plant == null
}

/**
 * Per-planting state inside an [AgentPlot]. [plantedByAgentId] is the recipient
 * for `CropDied` events — it is not an access-control field; any agent at the
 * node may tend or harvest a planted plot.
 */
data class PlantedCrop(
    val cropId: CropId,
    val plantedAtTick: Long,
    val lastTendedAtTick: Long,
    val plantedByAgentId: AgentId,
)
