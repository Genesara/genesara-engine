package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountInventoryStore
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * Inspect a tamed mount visible from the agent's current position. Same
 * visibility gate as `inspect_npc` — the mount must sit on a node currently
 * within the active simulation radius of the caller. Returns the full live
 * detail of the mount (vitals, gauges, equipped gear, owner, rider) the agent
 * needs to decide whether to mount, feed, attack, or claim.
 *
 */
@Component
internal class InspectMountTool(
    private val world: WorldQueryGateway,
    private val mounts: MountInstanceStore,
    private val mountCatalog: MountCatalog,
    private val itemInstances: AgentItemInstancesStore,
    private val mountInventory: MountInventoryStore,
    private val items: ItemLookup,
    private val activity: AgentActivityTracker,
) {
    @Tool(
        name = "inspect_mount",
        description = "Inspect a tamed mount visible from your current position. Vision-gated: " +
            "the mount must sit on a node currently in the active simulation set (the same set " +
            "`look_around` lists under `mounts`). Returns full vitals (hp/hunger/fatigue), equipped " +
            "gear by slot, owner, and current rider.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Target mount — wire-prefixed `mount:<uuid>` as returned by `look_around`.")
        mountId: String,
        toolContext: ToolContext,
    ): MountInspectResponse {
        touchActivity(toolContext, activity, "inspect_mount")
        val agentId = AgentContextHolder.current()

        val parsedMountId = PrefixedIds.parseMount(mountId)
            ?: return MountInspectResponse.error("bad_target_id", "mountId must be mount:<uuid>")

        val currentNode = world.locationOf(agentId)
            ?: return MountInspectResponse.error("not_in_world", "you are not spawned")

        val visibleNodes = world.nodesWithin(currentNode, 8)
        val mount = mounts.byNodes(visibleNodes).firstOrNull { it.id == parsedMountId }
            ?: return MountInspectResponse.error("not_visible", "mount is not in your visible range")

        val def = mountCatalog.byType(mount.type)
        val equipped = equippedGearFor(mount)
        val cargo = cargoFor(mount)

        return MountInspectResponse(
            kind = "mount",
            mount = MountInspectView(
                id = PrefixedIds.encodeMount(mount.id),
                type = mount.type.value,
                displayName = def?.displayName ?: mount.type.value,
                nodeId = mount.nodeId.value,
                hpCurrent = mount.hpCurrent,
                hpMax = mount.hpMax,
                hunger = mount.hunger,
                hungerMax = mount.hungerMax,
                fatigue = mount.fatigue,
                fatigueMax = mount.fatigueMax,
                owner = mount.ownerAgentId?.let(PrefixedIds::encodeAgent),
                rider = mount.mountedByAgentId?.let(PrefixedIds::encodeAgent),
                equipped = equipped,
                cargo = cargo,
            ),
        )
    }

    /**
     * Gear lookup goes via `byEquippedOnMount(mount.id)` so a released mount
     * (`ownerAgentId == null`) still surfaces its equipped gear — the rows
     * persist with the prior owner's `agent_id` and `equipped_on_mount_id`
     * pointing here. Anyone considering a `claim_transport` needs to see
     * what's still rigged on it.
     */
    private fun equippedGearFor(mount: Mount): Map<String, String> =
        itemInstances.byEquippedOnMount(mount.id)
            .asSequence()
            .filter { it.equippedMountSlot != null }
            .associate { gear ->
                val slot = gear.equippedMountSlot!!.name
                val display = items.byId(gear.itemId)?.displayName ?: gear.itemId.value
                slot to display
            }

    private fun cargoFor(mount: Mount): MountCargoView {
        val resources = mountInventory.byMount(mount.id).map { (itemId, quantity) ->
            MountCargoResourceView(itemId = itemId.value, quantity = quantity)
        }
        val stowed = itemInstances.byStowedOnMount(mount.id).map { instance ->
            MountCargoStowedView(
                instanceId = instance.instanceId.toString(),
                itemId = instance.itemId.value,
            )
        }
        return MountCargoView(resources = resources, stowed = stowed)
    }
}
