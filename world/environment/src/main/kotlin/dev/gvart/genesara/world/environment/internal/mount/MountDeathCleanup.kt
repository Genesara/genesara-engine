package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountInventoryStore
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.WorldEvent
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Side-effect applier for mount death. Both death paths
 * ([reduceAttackMount] combat death, [MountMaintenanceSweep] starvation
 * death) call [applyDeath] **before** [dev.gvart.genesara.world.MountInstanceStore.delete]
 * so the inventory + stowed-instances reads land before the row vanishes.
 *
 * §16 canon: equipped MountGear (BARDING / SADDLE / HARNESS) is destroyed
 * with the mount — gear rows are deleted with no ground drop. Cargo —
 * stackable resources in `mount_inventory` and per-instance items pointed
 * at via `stowed_in_mount_id` — drops at the mount's node so survivors can
 * pick it up. Stackable cargo's `mount_inventory` rows cascade-delete via
 * the V27 FK once the mount row is deleted; per-instance rows are deleted
 * explicitly here because they live on `agent_item_instances` (no
 * cascading FK against `mounts`).
 */
@Component
class MountDeathCleanup(
    private val instances: AgentItemInstancesStore,
    private val mountInventory: MountInventoryStore,
    private val groundItems: GroundItemStore,
) {

    fun applyDeath(mount: Mount, killer: AgentId?, tick: Long, causedBy: UUID?): List<WorldEvent> {
        val snapshot = instances.instancesOnMount(mount.id)
        val events = mutableListOf<WorldEvent>()

        for (instance in snapshot.stowed) {
            val drop = instance.toDroppedItemView() ?: continue
            groundItems.deposit(mount.nodeId, drop, tick)
            instances.delete(instance.instanceId)
            events += EconomyEvent.ItemDroppedOnGround(
                at = mount.nodeId,
                byAgent = instance.agentId,
                drop = drop,
                tick = tick,
                causedBy = causedBy,
            )
        }

        for (gear in snapshot.equipped) {
            instances.delete(gear.instanceId)
        }

        val stackCargo = mountInventory.byMount(mount.id)
        if (stackCargo.isNotEmpty()) {
            val attribution = killer ?: mount.mountedByAgentId
            for ((itemId, qty) in stackCargo) {
                val drop = DroppedItemView.Stackable(
                    dropId = UUID.randomUUID(),
                    item = itemId,
                    quantity = qty,
                )
                groundItems.deposit(mount.nodeId, drop, tick)
                events += EconomyEvent.ItemDroppedOnGround(
                    at = mount.nodeId,
                    byAgent = attribution,
                    drop = drop,
                    tick = tick,
                    causedBy = causedBy,
                )
            }
        }

        return events
    }

    private fun ItemInstance.toDroppedItemView(): DroppedItemView? = when (this) {
        is ItemInstance.Equipment -> DroppedItemView.Equipment(
            dropId = UUID.randomUUID(),
            item = itemId,
            instanceId = instanceId,
            rarity = rarity,
            durabilityCurrent = durabilityCurrent,
            durabilityMax = durabilityMax,
            creatorAgentId = creatorAgentId?.id,
            createdAtTick = createdAtTick,
        )
        is ItemInstance.MountGear -> DroppedItemView.Equipment(
            dropId = UUID.randomUUID(),
            item = itemId,
            instanceId = instanceId,
            rarity = rarity,
            durabilityCurrent = durabilityCurrent,
            durabilityMax = durabilityMax,
            creatorAgentId = creatorAgentId?.id,
            createdAtTick = createdAtTick,
        )
        is ItemInstance.Key -> null
    }
}
