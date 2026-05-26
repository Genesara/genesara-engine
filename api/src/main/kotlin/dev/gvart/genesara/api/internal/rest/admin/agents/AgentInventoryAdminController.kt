package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AdjustResult
import dev.gvart.genesara.world.AgentInventoryAdminStore
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipRejection
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentService
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.UnequipResult
import dev.gvart.genesara.world.WorldQueryGateway
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin/agents/{agentId}")
internal class AgentInventoryAdminController(
    private val items: ItemLookup,
    private val instances: AgentItemInstancesStore,
    private val inventoryAdmin: AgentInventoryAdminStore,
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    private val equipment: EquipmentService,
    private val tick: TickClock,
    private val audit: AdminAuditLog,
) {

    @GetMapping("/inventory")
    fun listInventory(@PathVariable agentId: UUID): InventoryListResponse {
        val target = requireAgent(agentId)
        return InventoryListResponse(
            entries = world.inventoryOf(target).entries.map(InventoryEntry::toDto),
        )
    }

    @PostMapping("/inventory")
    fun adjustInventory(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @Valid @RequestBody req: AdjustInventoryRequest,
    ): InventoryEntryDto {
        val target = requireAgent(agentId)
        val itemId = ItemId(req.itemId)
        if (items.byId(itemId) == null) throw notFound("item ${req.itemId} is not in the catalog")
        if (req.delta == 0) throw badRequest("delta must be non-zero")

        val outcome = inventoryAdmin.adjust(target, itemId, req.delta)
        val after = when (outcome) {
            is AdjustResult.Adjusted -> outcome.quantityAfter
            is AdjustResult.Insufficient -> throw badRequest(
                "agent does not have enough ${req.itemId} (has ${outcome.have}, asked to remove ${outcome.asked})",
            )
        }

        audit.record(
            adminId = admin.id,
            action = "agent.inventory.adjust",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf("itemId" to req.itemId, "delta" to req.delta, "quantityAfter" to after),
            tick = tick.currentTick(),
        )
        return InventoryEntryDto(itemId = req.itemId, quantity = after)
    }

    @DeleteMapping("/inventory/{itemId}")
    fun removeInventoryStack(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable itemId: String,
    ): ResponseEntity<Void> {
        val target = requireAgent(agentId)
        val parsedItemId = ItemId(itemId)
        val priorQty = inventoryAdmin.removeAll(target, parsedItemId)
        audit.record(
            adminId = admin.id,
            action = "agent.inventory.removeAll",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf("itemId" to itemId, "quantityRemoved" to priorQty),
            tick = tick.currentTick(),
        )
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/equipment")
    fun createEquipment(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @Valid @RequestBody req: CreateEquipmentRequest,
    ): ResponseEntity<EquipmentInstanceDto> {
        val target = requireAgent(agentId)

        val item = items.byId(ItemId(req.itemId))
            ?: throw notFound("item ${req.itemId} is not in the catalog")
        if (item.category != ItemCategory.EQUIPMENT) {
            throw badRequest("item ${req.itemId} is not EQUIPMENT-category")
        }
        val maxDurability = item.maxDurability
            ?: throw badRequest("item ${req.itemId} has no maxDurability — cannot seed an instance")

        val durabilityCurrent = req.durabilityCurrent ?: maxDurability
        if (durabilityCurrent > maxDurability) {
            throw badRequest("durabilityCurrent ($durabilityCurrent) must be in 0..$maxDurability")
        }

        val now = tick.currentTick()
        val instance = ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = target,
            itemId = item.id,
            rarity = req.rarity ?: item.rarity,
            durabilityCurrent = durabilityCurrent,
            durabilityMax = maxDurability,
            creatorAgentId = req.creatorAgentId?.let(::AgentId),
            createdAtTick = now,
        )
        instances.insert(instance)
        audit.record(
            adminId = admin.id,
            action = "agent.equipment.create",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf(
                "instanceId" to instance.instanceId.toString(),
                "itemId" to instance.itemId.value,
                "rarity" to instance.rarity.name,
                "durabilityCurrent" to instance.durabilityCurrent,
                "durabilityMax" to instance.durabilityMax,
                "creatorAgentId" to instance.creatorAgentId?.id?.toString(),
            ),
            tick = now,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(instance.toDto())
    }

    @PatchMapping("/equipment/{instanceId}")
    fun patchEquipment(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable instanceId: UUID,
        @Valid @RequestBody req: PatchEquipmentRequest,
    ): EquipmentInstanceDto {
        val target = requireAgent(agentId)
        val existing = instances.findById(instanceId) as? ItemInstance.Equipment
            ?: throw notFound("equipment instance $instanceId not found")
        if (existing.agentId != target) {
            throw notFound("equipment instance $instanceId not found")
        }

        val newMax = req.durabilityMax ?: existing.durabilityMax
        val newCurrent = req.durabilityCurrent ?: existing.durabilityCurrent
        if (newMax <= 0) throw badRequest("durabilityMax ($newMax) must be positive")
        if (newCurrent !in 0..newMax) {
            throw badRequest("durabilityCurrent ($newCurrent) must be in 0..$newMax")
        }

        val updated = instances.updateEquipment(
            instanceId = instanceId,
            rarity = req.rarity,
            durabilityCurrent = req.durabilityCurrent,
            durabilityMax = req.durabilityMax,
        ) ?: throw notFound("equipment instance $instanceId not found")
        audit.record(
            adminId = admin.id,
            action = "agent.equipment.patch",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf(
                "instanceId" to instanceId.toString(),
                "rarity" to req.rarity?.name,
                "durabilityCurrent" to req.durabilityCurrent,
                "durabilityMax" to req.durabilityMax,
            ),
            tick = tick.currentTick(),
        )
        return updated.toDto()
    }

    @DeleteMapping("/equipment/{instanceId}")
    fun deleteEquipment(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable instanceId: UUID,
    ): ResponseEntity<Void> {
        val target = requireAgent(agentId)
        val existing = instances.findById(instanceId) as? ItemInstance.Equipment
            ?: throw notFound("equipment instance $instanceId not found")
        if (existing.agentId != target) {
            throw notFound("equipment instance $instanceId not found")
        }
        instances.delete(instanceId)
        audit.record(
            adminId = admin.id,
            action = "agent.equipment.delete",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf("instanceId" to instanceId.toString(), "itemId" to existing.itemId.value),
            tick = tick.currentTick(),
        )
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/equipment/{instanceId}/equip")
    fun equip(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable instanceId: UUID,
        @RequestBody(required = false) req: EquipRequest?,
    ): EquipmentInstanceDto {
        val target = requireAgent(agentId)
        val existing = instances.findById(instanceId) as? ItemInstance.Equipment
            ?: throw notFound("equipment instance $instanceId not found")
        if (existing.agentId != target) {
            throw notFound("equipment instance $instanceId not found")
        }
        val slot = req?.slot ?: defaultSlotFor(existing)

        val result = equipment.equip(target, instanceId, slot)
        val equipped = when (result) {
            is EquipResult.Equipped -> result.instance
            is EquipResult.Rejected -> throw badRequest(rejectionDetail(result))
        }
        audit.record(
            adminId = admin.id,
            action = "agent.equipment.equip",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf("instanceId" to instanceId.toString(), "slot" to slot.name),
            tick = tick.currentTick(),
        )
        return equipped.toDto()
    }

    @DeleteMapping("/equipment/{instanceId}/equip")
    fun unequip(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable instanceId: UUID,
    ): EquipmentInstanceDto {
        val target = requireAgent(agentId)
        val existing = instances.findById(instanceId) as? ItemInstance.Equipment
            ?: throw notFound("equipment instance $instanceId not found")
        if (existing.agentId != target) {
            throw notFound("equipment instance $instanceId not found")
        }
        val slot = existing.equippedInSlot
            ?: throw badRequest("equipment instance $instanceId is not equipped")

        val result = equipment.unequip(target, slot)
        val unequipped = when (result) {
            is UnequipResult.Unequipped -> result.instance
            is UnequipResult.SlotEmpty -> throw badRequest("slot $slot was empty")
        }
        audit.record(
            adminId = admin.id,
            action = "agent.equipment.unequip",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf("instanceId" to instanceId.toString(), "slot" to slot.name),
            tick = tick.currentTick(),
        )
        return unequipped.toDto()
    }

    private fun requireAgent(agentId: UUID): AgentId {
        val id = AgentId(agentId)
        if (agents.find(id) == null) throw notFound("agent $agentId is not registered")
        return id
    }

    private fun defaultSlotFor(instance: ItemInstance.Equipment): EquipSlot {
        val item = items.byId(instance.itemId)
            ?: throw badRequest("item ${instance.itemId.value} is not in the catalog")
        return item.validSlots.firstOrNull()
            ?: throw badRequest("item ${instance.itemId.value} has no valid slots — specify slot explicitly")
    }

    private fun rejectionDetail(result: EquipResult.Rejected): String =
        result.detail?.let { "${result.reason.name}: $it" } ?: result.reason.name

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
}

data class AdjustInventoryRequest(
    @field:NotBlank val itemId: String,
    val delta: Int,
)

data class CreateEquipmentRequest(
    @field:NotBlank val itemId: String,
    val rarity: Rarity? = null,
    @field:PositiveOrZero val durabilityCurrent: Int? = null,
    val creatorAgentId: UUID? = null,
)

data class PatchEquipmentRequest(
    val rarity: Rarity? = null,
    @field:PositiveOrZero val durabilityCurrent: Int? = null,
    @field:PositiveOrZero val durabilityMax: Int? = null,
)

data class EquipRequest(val slot: EquipSlot? = null)

data class InventoryListResponse(val entries: List<InventoryEntryDto>)

data class InventoryEntryDto(val itemId: String, val quantity: Int)

data class EquipmentInstanceDto(
    val instanceId: UUID,
    val itemId: String,
    val rarity: Rarity,
    val durabilityCurrent: Int,
    val durabilityMax: Int,
    val creatorAgentId: UUID?,
    val createdAtTick: Long,
    val equippedInSlot: EquipSlot?,
)

private fun InventoryEntry.toDto() = InventoryEntryDto(itemId = itemId.value, quantity = quantity)

private fun ItemInstance.Equipment.toDto() = EquipmentInstanceDto(
    instanceId = instanceId,
    itemId = itemId.value,
    rarity = rarity,
    durabilityCurrent = durabilityCurrent,
    durabilityMax = durabilityMax,
    creatorAgentId = creatorAgentId?.id,
    createdAtTick = createdAtTick,
    equippedInSlot = equippedInSlot,
)
