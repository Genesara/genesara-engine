package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Sync service for moving cargo on and off a mount. Owner-gated and same-node
 * gated — mount cargo is a sensitive op, not a public hitch.
 *
 * Stackable resources route through `mount_inventory`; per-instance items
 * (EQUIPMENT, KEY, MOUNT_GEAR) route through `agent_item_instances.stowed_in_mount_id`.
 * The carry-cap (`mountDef.carryCapacityGrams` + HARNESS bonus) is checked
 * against the sum of both stores' weights.
 */
interface MountCargoService {

    fun storeResource(agentId: AgentId, mountId: MountId, itemId: ItemId, quantity: Int): MountCargoResult

    fun takeResource(agentId: AgentId, mountId: MountId, itemId: ItemId, quantity: Int): MountCargoResult

    fun storeInstance(agentId: AgentId, mountId: MountId, instanceId: UUID): MountCargoResult

    fun takeInstance(agentId: AgentId, mountId: MountId, instanceId: UUID): MountCargoResult
}

/** Outcome of a [MountCargoService] call. */
sealed interface MountCargoResult {
    data object Stored : MountCargoResult
    data object Taken : MountCargoResult
    data class Rejected(val reason: MountCargoRejection, val detail: String? = null) : MountCargoResult
}

enum class MountCargoRejection {
    /** No mount with the given id exists. */
    MOUNT_NOT_FOUND,
    /** Mount exists but is owned by a different agent (or unowned). */
    NOT_YOUR_MOUNT,
    /** Agent and mount aren't at the same node. */
    NOT_SAME_NODE,
    /** Cargo-capacity check failed — the requested store would push total weight above the cap. */
    OVER_CAPACITY,
    /** Catalog lookup for the mount type returned null (state corruption). */
    UNKNOWN_MOUNT_TYPE,
    /** Catalog lookup for the item returned null (state corruption). */
    UNKNOWN_ITEM,
    /** Stackable path: agent inventory holds fewer than the requested quantity. */
    INSUFFICIENT_INVENTORY,
    /** Stackable path: mount cargo holds fewer than the requested quantity. */
    INSUFFICIENT_CARGO,
    /** Per-instance path: the instance id doesn't exist. */
    INSTANCE_NOT_FOUND,
    /** Per-instance path: the instance is owned by a different agent. */
    NOT_YOUR_INSTANCE,
    /** Per-instance path: the instance is currently equipped (agent slot or mount slot). */
    INSTANCE_EQUIPPED,
    /** Per-instance path: the instance isn't stowed on the given mount (mismatch on take). */
    INSTANCE_NOT_STOWED_HERE,
    /** Quantity must be positive. */
    INVALID_QUANTITY,
}
