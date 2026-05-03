package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class InspectTool(
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    private val classes: ClassPropertiesLookup,
    private val items: ItemLookup,
    private val activity: AgentActivityTracker,
    private val tick: TickClock,
    private val buildings: BuildingsLookup,
    private val buildingDefs: BuildingDefLookup,
    private val chestContents: ChestContentsStore,
) {

    @Tool(
        name = "inspect",
        description = "Inspect a single target (node, agent, or item) in detail. " +
            "Vision-gated: nodes must be within sight, agents must be in the same node, " +
            "items must be in the agent's own inventory. Response depth scales with Perception.",
    )
    fun invoke(req: InspectRequest, toolContext: ToolContext): InspectResponse {
        touchActivity(toolContext, activity, "inspect")
        val agentId = AgentContextHolder.current()
        val agent = agents.find(agentId) ?: error("Agent not registered: $agentId")
        val depth = inspectDepthFor(agent.attributes.perception)

        val targetType = req.targetType?.lowercase()?.trim()
        val targetId = req.targetId?.trim()
        if (targetType.isNullOrBlank() || targetId.isNullOrBlank()) {
            return errorResponse(depth, InspectError.BAD_TARGET_TYPE, "targetType and targetId are required")
        }

        return when (targetType) {
            "node" -> inspectNode(agentId, targetId, depth)
            "agent" -> inspectAgent(agentId, targetId, depth)
            "item" -> inspectItem(agentId, targetId, depth)
            "building" -> inspectBuilding(agentId, targetId, depth)
            else -> errorResponse(depth, InspectError.BAD_TARGET_TYPE, "unknown targetType: $targetType")
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

    private fun isNodeWithinSight(agent: Agent, currentNodeId: NodeId, nodeId: NodeId): Boolean {
        val sight = classes.sightRange(agent.classId)
        return nodeId in world.nodesWithin(currentNodeId, sight)
    }

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
        val targetUuid = runCatching { UUID.fromString(targetId) }.getOrNull()
            ?: return errorResponse(depth, InspectError.BAD_TARGET_ID, "agent id must be a UUID")
        val targetAgentId = AgentId(targetUuid)
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
        val itemId = ItemId(targetId)
        val item = items.byId(itemId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "item not found in catalog")
        // TODO(equipment-slot): also resolve a per-instance lookup against
        //   EquipmentInstanceStore so an agent can inspect a specific equipment
        //   instance (with its rolled rarity + live current durability). For now
        //   only the stackable inventory is checked.
        val inventory = world.inventoryOf(agentId)
        val held = inventory.entries.firstOrNull { it.itemId == itemId }
            ?: return errorResponse(depth, InspectError.NOT_IN_INVENTORY, "item is not in your inventory")

        return InspectResponse(
            kind = "item",
            depth = depth.name.lowercase(),
            item = ItemInspectView(
                itemId = item.id.value,
                displayName = item.displayName,
                description = item.description,
                category = item.category.name,
                quantity = held.quantity,
                weightPerUnit = if (depth != InspectDepth.SHALLOW) item.weightPerUnit else null,
                maxStack = if (depth != InspectDepth.SHALLOW) item.maxStack else null,
                regenerating = if (depth != InspectDepth.SHALLOW) item.regenerating else null,
                rarity = if (depth != InspectDepth.SHALLOW) item.rarity.name else null,
                maxDurability = if (depth != InspectDepth.SHALLOW) item.maxDurability else null,
                gatheringSkill = if (depth == InspectDepth.EXPERT) item.gatheringSkill else null,
            ),
        )
    }

    private fun projectAgent(target: Agent, body: BodyView, depth: InspectDepth): AgentInspectView {
        val classId = if (depth != InspectDepth.SHALLOW) target.classId?.name else null
        val hpBand = if (depth != InspectDepth.SHALLOW) bandOf(body.hp, body.maxHp) else null
        val staminaBand = if (depth != InspectDepth.SHALLOW) bandOf(body.stamina, body.maxStamina) else null
        // Mana is psionic-only: non-psionic classes have `maxMana == 0` and we hide the
        // pool entirely (canon: `Agent.mana` is null for non-psionic).
        val manaBand = if (depth != InspectDepth.SHALLOW && body.maxMana > 0) {
            bandOf(body.mana, body.maxMana)
        } else null
        // TODO(combat): populate Bleed/Burn/Stun/Poison once Phase 2 status effects ship.
        val activeEffects = if (depth == InspectDepth.EXPERT) emptyList<String>() else null
        return AgentInspectView(
            id = target.id.id.toString(),
            name = target.name,
            race = target.race.value,
            level = target.level,
            classId = classId,
            hpBand = hpBand,
            staminaBand = staminaBand,
            manaBand = manaBand,
            activeEffects = activeEffects,
        )
    }

    private fun inspectBuilding(agentId: AgentId, targetId: String, depth: InspectDepth): InspectResponse {
        val instanceId = runCatching { UUID.fromString(targetId) }.getOrNull()
            ?: return errorResponse(depth, InspectError.BAD_TARGET_ID, "building id must be a UUID")
        val building = buildings.byId(instanceId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "building not found")

        val currentNodeId = world.locationOf(agentId)
            ?: return errorResponse(depth, InspectError.NOT_VISIBLE, "agent is not in the world")
        val agent = agents.find(agentId) ?: error("Agent disappeared mid-call: $agentId")
        if (!isNodeWithinSight(agent, currentNodeId, building.nodeId)) {
            return errorResponse(depth, InspectError.NOT_VISIBLE, "building is outside sight range")
        }

        return InspectResponse(
            kind = "building",
            depth = depth.name.lowercase(),
            building = projectBuilding(agentId, building, depth),
        )
    }

    private fun projectBuilding(
        agentId: AgentId,
        building: Building,
        depth: InspectDepth,
    ): BuildingInspectView {
        val def = buildingDefs.byType(building.type)
        val isOwner = building.builtByAgentId == agentId
        val isExpert = depth == InspectDepth.EXPERT
        val isDetailedPlus = depth != InspectDepth.SHALLOW
        val showChestContents = isExpert && isOwner && building.type == BuildingType.STORAGE_CHEST
        return BuildingInspectView(
            instanceId = building.instanceId.toString(),
            type = building.type.name,
            status = building.status.name,
            progressSteps = building.progressSteps,
            totalSteps = building.totalSteps,
            hpBand = bandOf(building.hpCurrent, building.hpMax),
            nodeId = if (isDetailedPlus) building.nodeId.value else null,
            builderAgentId = if (isDetailedPlus) building.builtByAgentId.id.toString() else null,
            hpCurrent = if (isDetailedPlus) building.hpCurrent else null,
            hpMax = if (isDetailedPlus) building.hpMax else null,
            lastProgressTick = if (isDetailedPlus) building.lastProgressTick else null,
            builtAtTick = if (isExpert) building.builtAtTick else null,
            requiredSkill = if (isExpert) def?.requiredSkill?.value else null,
            requiredSkillLevel = if (isExpert) def?.requiredSkillLevel else null,
            totalMaterials = if (isExpert) def?.totalMaterials?.toMaterialViews() else null,
            stepMaterials = if (isExpert) def?.stepMaterials?.map { it.toMaterialViews() } else null,
            chestContents = if (showChestContents) chestContents.contentsOf(building.instanceId).toMaterialViews() else null,
        )
    }

    private fun Map<ItemId, Int>.toMaterialViews(): List<BuildingMaterialView> =
        entries.map { BuildingMaterialView(itemId = it.key.value, quantity = it.value) }

    private fun bandOf(current: Int, max: Int): String = when {
        max <= 0 -> "unknown"
        current <= 0 -> "dead"
        current * 10 < max * 3 -> "low"
        current * 10 < max * 7 -> "mid"
        else -> "high"
    }

    private fun errorResponse(depth: InspectDepth, code: String, message: String): InspectResponse =
        InspectResponse(
            kind = "error",
            depth = depth.name.lowercase(),
            error = InspectError(code = code, message = message),
        )
}
