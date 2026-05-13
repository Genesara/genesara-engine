package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * A cultivation plot bound 1:1 to a [Building] of type [BuildingType.FARM_PLOT].
 * The row is created on FARM_PLOT build completion and removed only when its
 * building is demolished (`ON DELETE CASCADE`).
 */
data class AgentPlot(
    val plotId: UUID,
    val buildingInstanceId: UUID,
    val agentId: AgentId,
    val nodeId: NodeId,
    val plant: PlantedCrop?,
) {
    val isEmpty: Boolean get() = plant == null
}

data class PlantedCrop(
    val cropId: CropId,
    val plantedAtTick: Long,
    val lastTendedAtTick: Long,
)
