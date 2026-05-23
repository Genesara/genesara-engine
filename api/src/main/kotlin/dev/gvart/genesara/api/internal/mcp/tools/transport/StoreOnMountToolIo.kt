package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.world.MountCargoRejection

internal enum class StoreOnMountOutcome { STORED, REJECTED }
internal enum class TakeFromMountOutcome { TAKEN, REJECTED }

internal data class StoreOnMountResponse(
    val outcome: StoreOnMountOutcome,
    val transportId: String,
    val itemId: String? = null,
    val quantity: Int? = null,
    val instanceId: String? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun storedResource(transportId: String, itemId: String, quantity: Int): StoreOnMountResponse =
            StoreOnMountResponse(
                outcome = StoreOnMountOutcome.STORED,
                transportId = transportId,
                itemId = itemId,
                quantity = quantity,
            )

        fun storedInstance(transportId: String, instanceId: String): StoreOnMountResponse =
            StoreOnMountResponse(
                outcome = StoreOnMountOutcome.STORED,
                transportId = transportId,
                instanceId = instanceId,
            )

        fun rejected(
            transportId: String,
            reason: String,
            detail: String?,
            itemId: String? = null,
            quantity: Int? = null,
            instanceId: String? = null,
        ): StoreOnMountResponse = StoreOnMountResponse(
            outcome = StoreOnMountOutcome.REJECTED,
            transportId = transportId,
            itemId = itemId,
            quantity = quantity,
            instanceId = instanceId,
            reason = reason,
            detail = detail,
        )
    }
}

internal data class TakeFromMountResponse(
    val outcome: TakeFromMountOutcome,
    val transportId: String,
    val itemId: String? = null,
    val quantity: Int? = null,
    val instanceId: String? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun takenResource(transportId: String, itemId: String, quantity: Int): TakeFromMountResponse =
            TakeFromMountResponse(
                outcome = TakeFromMountOutcome.TAKEN,
                transportId = transportId,
                itemId = itemId,
                quantity = quantity,
            )

        fun takenInstance(transportId: String, instanceId: String): TakeFromMountResponse =
            TakeFromMountResponse(
                outcome = TakeFromMountOutcome.TAKEN,
                transportId = transportId,
                instanceId = instanceId,
            )

        fun rejected(
            transportId: String,
            reason: String,
            detail: String?,
            itemId: String? = null,
            quantity: Int? = null,
            instanceId: String? = null,
        ): TakeFromMountResponse = TakeFromMountResponse(
            outcome = TakeFromMountOutcome.REJECTED,
            transportId = transportId,
            itemId = itemId,
            quantity = quantity,
            instanceId = instanceId,
            reason = reason,
            detail = detail,
        )
    }
}

internal fun MountCargoRejection.toReasonCode(): String = name.lowercase()
