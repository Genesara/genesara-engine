package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * A single physical instance of a built structure attached to a node. The
 * domain analog of an `agent_equipment_instances` row — per-instance UUID,
 * lifecycle state, originator signature.
 *
 * Backed by `world.node_buildings`. Built up step-by-step: the agent submits
 * one `BuildStructure` command per step, paying per-step materials + stamina,
 * until [progressSteps] reaches [totalSteps] and the row flips to
 * [BuildingStatus.ACTIVE]. Behavioral effects gate on `ACTIVE`.
 */
data class Building(
    val instanceId: UUID,
    val nodeId: NodeId,
    val type: BuildingType,
    val status: BuildingStatus,
    val builtByAgentId: AgentId,
    val builtAtTick: Long,
    val lastProgressTick: Long,
    val progressSteps: Int,
    val totalSteps: Int,
    val hpCurrent: Int,
    val hpMax: Int,
) {
    init {
        require(totalSteps > 0) { "totalSteps ($totalSteps) must be positive" }
        require(progressSteps in 0..totalSteps) {
            "progressSteps ($progressSteps) must be in 0..totalSteps ($totalSteps)"
        }
        require(hpMax > 0) { "hpMax ($hpMax) must be positive" }
        require(hpCurrent in 0..hpMax) {
            "hpCurrent ($hpCurrent) must be in 0..hpMax ($hpMax)"
        }
        // Mirror the SQL CHECK so a hand-built domain object can't drift from the
        // schema invariant: ACTIVE iff fully built.
        val isActive = status == BuildingStatus.ACTIVE
        val isComplete = progressSteps == totalSteps
        require(isActive == isComplete) {
            "status ($status) must match completion (progressSteps=$progressSteps, totalSteps=$totalSteps)"
        }
    }

    val isActive: Boolean get() = status == BuildingStatus.ACTIVE
}
