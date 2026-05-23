package dev.gvart.genesara.api.internal.mcp.tools.inspect

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MountInspectResponse(
    val kind: String,
    val mount: MountInspectView? = null,
    val error: MountInspectError? = null,
) {
    internal companion object {
        internal fun error(code: String, message: String): MountInspectResponse =
            MountInspectResponse(kind = "error", error = MountInspectError(code, message))
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MountInspectView(
    val id: String,
    val type: String,
    val displayName: String,
    val nodeId: Long,
    val hpCurrent: Int,
    val hpMax: Int,
    val hunger: Int,
    val hungerMax: Int,
    val fatigue: Int,
    val fatigueMax: Int,
    val owner: String?,
    val rider: String?,
    val equipped: Map<String, String>,
    val cargo: MountCargoView?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MountCargoView(
    val resources: List<MountCargoResourceView> = emptyList(),
    val stowed: List<MountCargoStowedView> = emptyList(),
)

data class MountCargoResourceView(val itemId: String, val quantity: Int)

data class MountCargoStowedView(val instanceId: String, val itemId: String)

data class MountInspectError(val code: String, val message: String)
