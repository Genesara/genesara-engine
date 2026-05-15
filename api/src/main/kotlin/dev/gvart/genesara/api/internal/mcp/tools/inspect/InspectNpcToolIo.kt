package dev.gvart.genesara.api.internal.mcp.tools.inspect

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NpcInspectResponse(
    val kind: String,
    val depth: String,
    val npc: NpcInspectView? = null,
    val error: NpcInspectError? = null,
) {
    internal companion object {
        internal fun error(depth: InspectDepth, code: String, message: String): NpcInspectResponse =
            NpcInspectResponse(
                kind = "error",
                depth = depth.name.lowercase(),
                error = NpcInspectError(code, message),
            )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NpcInspectView(
    val id: String,
    val type: String,
    val displayName: String,
    val nodeId: Long,
    val hpBand: String,
    val aggressionProfile: String? = null,
    val hpCurrent: Int? = null,
    val hpMax: Int? = null,
    val attackIntervalTicks: Int? = null,
    val damage: Int? = null,
    val damageType: String? = null,
    val defense: Int? = null,
    val dodgeChancePercent: Int? = null,
    val range: Int? = null,
    val territoryRadius: Int? = null,
)

data class NpcInspectError(val code: String, val message: String)
