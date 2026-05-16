package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.projection.vitalBand
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.equipment.views.equipmentStatsViewOf
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingBar
import dev.gvart.genesara.world.BuildingBarsStore
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.VisibleNodes
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class InspectTool(
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    private val vision: VisibleNodes,
    private val items: ItemLookup,
    private val activity: AgentActivityTracker,
    private val tick: TickClock,
    private val buildings: BuildingsLookup,
    private val buildingDefs: BuildingDefLookup,
    private val buildingBars: BuildingBarsStore,
    private val chestContents: ChestContentsStore,
    private val equipmentInstances: AgentItemInstancesStore,
    private val equipmentSets: EquipmentSetLookup,
    private val gateStates: BuildingGateStateStore,
) {

    @Tool(
        name = "inspect",
        description = "Inspect a single target (node, agent, item, or building) in detail. " +
            "Vision-gated: nodes and buildings must be within sight, agents must be in the same node, " +
            "items must be in the agent's own inventory. There is no caller-supplied depth — the level " +
            "of detail is derived from the calling agent's Perception attribute and reflected in the " +
            "response's `depth` field.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Kind of target to inspect. One of NODE, AGENT, ITEM, BUILDING.")
        targetType: InspectTargetType,
        @ToolParam(
            required = true,
            description = "Target id. For NODE this is the numeric BIGINT id; for AGENT this is the wire-prefixed " +
                "`agent:<uuid>`; for BUILDING this is the building instance UUID; for ITEM this is either the " +
                "ItemId string (stackable resources) or the equipment instance UUID.",
        )
        targetId: String,
        toolContext: ToolContext,
    ): InspectResponse {
        touchActivity(toolContext, activity, "inspect")
        val agentId = AgentContextHolder.current()
        val agent = agents.find(agentId) ?: error("Agent not registered: $agentId")
        val depth = inspectDepthFor(agent.attributes.perception)

        val trimmedTargetId = targetId.trim()
        if (trimmedTargetId.isEmpty()) {
            return errorResponse(depth, InspectError.BAD_TARGET_ID, "targetId must not be blank")
        }

        return when (targetType) {
            InspectTargetType.NODE -> inspectNode(agentId, trimmedTargetId, depth)
            InspectTargetType.AGENT -> inspectAgent(agentId, trimmedTargetId, depth)
            InspectTargetType.ITEM -> inspectItem(agentId, trimmedTargetId, depth)
            InspectTargetType.BUILDING -> {
                val instanceId = runCatching { UUID.fromString(trimmedTargetId) }.getOrNull()
                    ?: return errorResponse(depth, InspectError.BAD_TARGET_ID, "building id must be a UUID")
                inspectBuilding(agentId, instanceId, depth)
            }
        }
    }

    private fun inspectNode(agentId: AgentId, targetId: String, depth: InspectDepth): InspectResponse {
        val nodeIdLong = targetId.toLongOrNull()
            ?: return errorResponse(depth, InspectError.BAD_TARGET_ID, "node id must be numeric")
        val nodeId = NodeId(nodeIdLong)
        val node = world.node(nodeId) ?: return errorResponse(depth, InspectError.NOT_FOUND, "node not found")
        val region = world.region(node.regionId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "region not found")

        val currentNodeId = world.locationOf(agentId)
            ?: return errorResponse(depth, InspectError.NOT_VISIBLE, "agent is not in the world")
        val agent = agents.find(agentId) ?: error("Agent disappeared mid-call: $agentId")
        if (!isNodeWithinSight(agent, currentNodeId, nodeId)) {
            return errorResponse(depth, InspectError.NOT_VISIBLE, "node is outside sight range")
        }

        val isCurrent = nodeId == currentNodeId
        val resources = world.resourcesAt(nodeId, tick.currentTick())
        val resourceIds = resources.entries.keys.map { it.value }.sorted()
        val resourceQuantities = resourceQuantitiesFor(isCurrent, depth, resources)
        val expert = if (depth == InspectDepth.EXPERT) NodeExpertView(pvpEnabled = node.pvpEnabled) else null

        return InspectResponse(
            kind = "node",
            depth = depth.name.lowercase(),
            node = NodeInspectView(
                id = node.id.value,
                q = node.q,
                r = node.r,
                terrain = node.terrain.name,
                biome = region.biome?.name,
                climate = region.climate?.name,
                resources = resourceIds,
                resourceQuantities = resourceQuantities,
                expert = expert,
            ),
        )
    }

    private fun isNodeWithinSight(agent: Agent, currentNodeId: NodeId, nodeId: NodeId): Boolean =
        nodeId in vision.visibleNodesFor(agent, currentNodeId, buildings.byNode(currentNodeId))

    /**
     * Quantities surface only when the agent stands on the tile (matches `look_around`)
     * or the depth tier is DETAILED+ (perceptive agents pick up adjacent counts too).
     */
    private fun resourceQuantitiesFor(
        isCurrent: Boolean,
        depth: InspectDepth,
        resources: dev.gvart.genesara.world.NodeResources,
    ): List<ResourceQuantityView>? =
        if (isCurrent || depth != InspectDepth.SHALLOW) {
            resources.entries.values.map {
                ResourceQuantityView(
                    itemId = it.itemId.value,
                    quantity = it.quantity,
                    initialQuantity = it.initialQuantity,
                )
            }
        } else null

    private fun inspectAgent(agentId: AgentId, targetId: String, depth: InspectDepth): InspectResponse {
        val targetAgentId = PrefixedIds.parseAgent(targetId)
            ?: return errorResponse(depth, InspectError.BAD_TARGET_ID, "agent id must be agent:<uuid>")
        val target = agents.find(targetAgentId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "agent not found")

        val myNode = world.locationOf(agentId)
            ?: return errorResponse(depth, InspectError.NOT_VISIBLE, "calling agent is not in the world")
        val targetNode = world.activePositionOf(targetAgentId)
            ?: return errorResponse(depth, InspectError.NOT_VISIBLE, "target agent is offline")
        if (myNode != targetNode) {
            return errorResponse(depth, InspectError.NOT_VISIBLE, "target agent is not in your node")
        }

        // Presence without a body row is a state inconsistency (presence write without a
        // paired body upsert); surface as NOT_FOUND so callers can react rather than
        // silently emitting "unknown" bands.
        val body = world.bodyOf(targetAgentId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "target agent has no body — state inconsistency")
        return InspectResponse(
            kind = "agent",
            depth = depth.name.lowercase(),
            agent = projectAgent(target, body, depth),
        )
    }

    private fun inspectItem(agentId: AgentId, targetId: String, depth: InspectDepth): InspectResponse {
        val instanceUuid = runCatching { UUID.fromString(targetId) }.getOrNull()
        if (instanceUuid != null) {
            return inspectEquipmentInstance(agentId, instanceUuid, depth)
        }
        return inspectStackableItem(agentId, ItemId(targetId), depth)
    }

    private fun inspectStackableItem(agentId: AgentId, itemId: ItemId, depth: InspectDepth): InspectResponse {
        val item = items.byId(itemId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "item not found in catalog")
        val inventory = world.inventoryOf(agentId)
        val held = inventory.entries.firstOrNull { it.itemId == itemId }
            ?: return errorResponse(depth, InspectError.NOT_IN_INVENTORY, "item is not in your inventory")

        return InspectResponse(
            kind = "item",
            depth = depth.name.lowercase(),
            item = projectCatalogItem(item, quantity = held.quantity, depth = depth, instanceState = null),
        )
    }

    private fun inspectEquipmentInstance(agentId: AgentId, instanceId: UUID, depth: InspectDepth): InspectResponse {
        val instance = equipmentInstances.findById(instanceId) as? ItemInstance.Equipment
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "equipment instance not found")
        if (instance.agentId != agentId) {
            return errorResponse(depth, InspectError.NOT_IN_INVENTORY, "equipment instance is not in your inventory")
        }
        val item = items.byId(instance.itemId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "item not found in catalog")
        val instanceState = if (depth != InspectDepth.SHALLOW) instance.toInstanceStateView() else null
        return InspectResponse(
            kind = "item",
            depth = depth.name.lowercase(),
            item = projectCatalogItem(item, quantity = 1, depth = depth, instanceState = instanceState),
        )
    }

    private fun projectCatalogItem(
        item: Item,
        quantity: Int,
        depth: InspectDepth,
        instanceState: InstanceStateView?,
    ): ItemInspectView {
        val isDetailedPlus = depth != InspectDepth.SHALLOW
        return ItemInspectView(
            itemId = item.id.value,
            displayName = item.displayName,
            description = item.description,
            category = item.category.name,
            quantity = quantity,
            weightPerUnit = if (isDetailedPlus) item.weightPerUnit else null,
            maxStack = if (isDetailedPlus) item.maxStack else null,
            regenerating = if (isDetailedPlus) item.regenerating else null,
            rarity = if (isDetailedPlus) item.rarity.name else null,
            maxDurability = if (isDetailedPlus) item.maxDurability else null,
            harvestSkill = if (depth == InspectDepth.EXPERT) item.harvestSkill?.value else null,
            equipmentStats = if (isDetailedPlus) equipmentStatsViewOf(item) else null,
            instanceState = instanceState,
            equipmentSets = if (isDetailedPlus) equipmentSetIdsFor(item) else null,
        )
    }

    private fun equipmentSetIdsFor(item: Item): List<String>? {
        if (item.category != ItemCategory.EQUIPMENT) return null
        return equipmentSets.setsContaining(item.id).map { it.id.value }.sorted()
    }

    private fun ItemInstance.Equipment.toInstanceStateView(): InstanceStateView = InstanceStateView(
        rarity = rarity.name,
        durabilityCurrent = durabilityCurrent,
        durabilityMax = durabilityMax,
        creator = creatorAgentId?.let(PrefixedIds::encodeAgent),
    )

    private fun projectAgent(target: Agent, body: BodyView, depth: InspectDepth): AgentInspectView {
        val manaBand = if (body.maxMana > 0) bandOf(body.mana, body.maxMana) else null
        // TODO(combat): populate Bleed/Burn/Stun/Poison once Phase 2 status effects ship.
        val activeEffects = if (depth == InspectDepth.EXPERT) emptyList<String>() else null
        return AgentInspectView(
            id = PrefixedIds.encodeAgent(target.id),
            name = target.name,
            race = target.race.value,
            level = target.level,
            classId = target.classId?.name,
            hpBand = bandOf(body.hp, body.maxHp),
            staminaBand = bandOf(body.stamina, body.maxStamina),
            manaBand = manaBand,
            activeEffects = activeEffects,
        )
    }

    private fun inspectBuilding(agentId: AgentId, instanceId: UUID, depth: InspectDepth): InspectResponse {
        val building = buildings.byId(instanceId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "building not found")

        val currentNodeId = world.locationOf(agentId)
            ?: return errorResponse(depth, InspectError.NOT_VISIBLE, "agent is not in the world")
        val agent = agents.find(agentId) ?: error("Agent disappeared mid-call: $agentId")
        if (!isNodeWithinSight(agent, currentNodeId, building.nodeId)) {
            return errorResponse(depth, InspectError.NOT_VISIBLE, "building is outside sight range")
        }

        val sameNode = currentNodeId == building.nodeId
        val liveBars = liveBarsFor(building, sameNode)
        return InspectResponse(
            kind = "building",
            depth = depth.name.lowercase(),
            building = projectBuilding(agentId, building, depth, liveBars),
        )
    }

    /**
     * Batched-by-design even though `inspect` projects one building: the call routes
     * through `barsByInstances` rather than `barsByInstance`, matching the read-path
     * pattern other projections use (`look_around`'s `byNodes`). ACTIVE buildings skip
     * the side-table read — all bars are full by schema invariant.
     */
    private fun liveBarsFor(building: Building, sameNode: Boolean): List<BuildingBar>? {
        if (!sameNode || building.status != BuildingStatus.UNDER_CONSTRUCTION) return null
        return buildingBars.barsByInstances(setOf(building.instanceId))[building.instanceId].orEmpty()
    }

    private fun projectBuilding(
        agentId: AgentId,
        building: Building,
        depth: InspectDepth,
        liveBars: List<BuildingBar>?,
    ): BuildingInspectView {
        val def = buildingDefs.byType(building.type)
        val isOwner = building.builtByAgentId == agentId
        val showCatalogDetail = isOwner || depth == InspectDepth.EXPERT
        val showChestContents = isOwner && building.type == BuildingType.STORAGE_CHEST
        return BuildingInspectView(
            instanceId = building.instanceId.toString(),
            type = building.type.name,
            status = building.status.name,
            progressSteps = building.progressSteps,
            totalSteps = building.totalSteps,
            hpBand = vitalBand(building.hpCurrent, building.hpMax, zeroLabel = "destroyed"),
            nodeId = building.nodeId.value,
            builderAgentId = PrefixedIds.encodeAgent(building.builtByAgentId),
            hpCurrent = building.hpCurrent,
            hpMax = building.hpMax,
            lastProgressTick = building.lastProgressTick,
            builtAtTick = if (showCatalogDetail) building.builtAtTick else null,
            skillBars = if (showCatalogDetail) def?.skillBars?.map { bar ->
                BuildingSkillBarView(
                    skill = bar.skill.value,
                    requiredSkillLevel = bar.level,
                    steps = bar.steps,
                    materialsPerStep = bar.materialsPerStep.toMaterialViews(),
                )
            } else null,
            liveBars = liveBars?.map {
                BuildingBarProgressView(
                    skill = it.skill.value,
                    progressSteps = it.progressSteps,
                    totalSteps = it.totalSteps,
                )
            },
            chestContents = if (showChestContents) chestContents.contentsOf(building.instanceId).toMaterialViews() else null,
            isOpen = if (building.type == BuildingType.GATE && building.isActive) {
                gateStates.isOpen(building.instanceId)
            } else null,
        )
    }

    private fun Map<ItemId, Int>.toMaterialViews(): List<BuildingMaterialView> =
        entries.map { BuildingMaterialView(itemId = it.key.value, quantity = it.value) }

    private fun bandOf(current: Int, max: Int): String = vitalBand(current, max)

    private fun errorResponse(depth: InspectDepth, code: String, message: String): InspectResponse =
        InspectResponse(
            kind = "error",
            depth = depth.name.lowercase(),
            error = InspectError(code = code, message = message),
        )
}
