package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.loadout.EquipmentInstanceView
import dev.gvart.genesara.api.internal.mcp.tools.loadout.EquipmentSlotView
import dev.gvart.genesara.api.internal.mcp.tools.loadout.EquipmentView
import dev.gvart.genesara.api.internal.mcp.tools.loadout.GetLoadoutResponse
import dev.gvart.genesara.api.internal.mcp.tools.loadout.InventoryEntryView
import dev.gvart.genesara.api.internal.mcp.tools.loadout.ItemInstanceView
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/agents/{agentId}")
internal class AgentInventoryController(
    private val owned: OwnedAgentResolver,
    private val world: WorldQueryGateway,
    private val items: ItemLookup,
    private val instances: AgentItemInstancesStore,
) {

    data class InventoryResponse(val entries: List<InventoryEntryView>)

    @GetMapping("/inventory")
    fun inventory(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
    ): InventoryResponse {
        val agent = owned.resolve(player, agentId)
        return InventoryResponse(
            entries = world.inventoryOf(agent.id).entries.map {
                InventoryEntryView(
                    itemId = it.itemId.value,
                    quantity = it.quantity,
                    rarity = rarityFor(it.itemId),
                )
            },
        )
    }

    @GetMapping("/loadout")
    fun loadout(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
    ): GetLoadoutResponse {
        val agent = owned.resolve(player, agentId)
        val inventory = world.inventoryOf(agent.id)
        val all = instances.listByAgent(agent.id)
        val equipment = all.filterIsInstance<ItemInstance.Equipment>()
        val keys = all.filterIsInstance<ItemInstance.Key>()
        val (equippedList, stashList) = equipment.partition { it.equippedInSlot != null }
        val bySlot = equippedList.associateBy { it.equippedInSlot!! }
        return GetLoadoutResponse(
            stackable = inventory.entries.map {
                InventoryEntryView(
                    itemId = it.itemId.value,
                    quantity = it.quantity,
                    rarity = rarityFor(it.itemId),
                )
            },
            instances = keys.map { key ->
                ItemInstanceView(
                    instanceId = key.instanceId.toString(),
                    itemId = key.itemId.value,
                    category = "KEY",
                    rarity = rarityFor(key.itemId),
                    gateInstanceId = key.gateInstanceId.toString(),
                )
            },
            equipment = EquipmentView(
                slots = EquipSlot.entries.map { slot ->
                    EquipmentSlotView(slotId = slot.name, instance = bySlot[slot]?.toView())
                },
                stash = stashList.map { it.toView() },
            ),
        )
    }

    private fun rarityFor(itemId: ItemId): Rarity = items.byId(itemId)?.rarity ?: Rarity.COMMON

    private fun ItemInstance.Equipment.toView() = EquipmentInstanceView(
        instanceId = instanceId.toString(),
        itemId = itemId.value,
        rarity = rarity,
        durabilityCurrent = durabilityCurrent,
        durabilityMax = durabilityMax,
        creatorAgentId = creatorAgentId?.let(PrefixedIds::encodeAgent),
    )
}
