package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.world.BodyView
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
    private val activity: AgentActivityRegistry,
    private val tick: TickClock,
) {

    @Tool(
        name = "inspect",
        description = "Inspect a single target (node, agent, or item) in detail. " +
            "Vision-gated: nodes must be within sight, agents must be in the same node, " +
            "items must be in the agent's own inventory. Response depth scales with Perception.",
    )
    fun invoke(req: InspectRequest, toolContext: ToolContext): InspectResponse {
        touchActivity(toolContext, activity)
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
        val sight = classes.sightRange(agent.classId)
        val visible = world.nodesWithin(currentNodeId, sight)
        if (nodeId !in visible) {
            return errorResponse(depth, InspectError.NOT_VISIBLE, "node is outside sight range")
        }

        val isCurrent = nodeId == currentNodeId
        val resources = world.resourcesAt(nodeId, tick.currentTick())
        val resourceIds = resources.entries.keys.map { it.value }.sorted()
        // Quantities are visible when:
        //  - the agent is on the tile (always — this matches look_around), OR
        //  - DETAILED+ Perception (so a perceptive agent picks up adjacent counts too).
        val resourceQuantities = if (isCurrent || depth != InspectDepth.SHALLOW) {
            resources.entries.values.map {
                ResourceQuantityView(
                    itemId = it.itemId.value,
                    quantity = it.quantity,
                    initialQuantity = it.initialQuantity,
                )
            }
        } else null
        val expert = if (depth == InspectDepth.EXPERT) {
            // Stamina-cost-multiplier hint deferred — exposing it cleanly would widen the
            // BalanceLookup contract through to the api module. Phase 1 / 2 can layer that
            // on top of the existing depth tier without a payload-shape change.
            NodeExpertView(pvpEnabled = node.pvpEnabled)
        } else null

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

    private fun inspectAgent(agentId: AgentId, targetId: String, depth: InspectDepth): InspectResponse {
        val targetUuid = runCatching { UUID.fromString(targetId) }.getOrNull()
            ?: return errorResponse(depth, InspectError.BAD_TARGET_ID, "agent id must be a UUID")
        val targetAgentId = AgentId(targetUuid)
        val target = agents.find(targetAgentId)
            ?: return errorResponse(depth, InspectError.NOT_FOUND, "agent not found")

        // Same-node only — presence-gated using the active position. Self-inspection
        // takes the same path (the agent is trivially in their own node).
        val myNode = world.locationOf(agentId)
            ?: return errorResponse(depth, InspectError.NOT_VISIBLE, "calling agent is not in the world")
        val targetNode = world.activePositionOf(targetAgentId)
            ?: return errorResponse(depth, InspectError.NOT_VISIBLE, "target agent is offline")
        if (myNode != targetNode) {
            return errorResponse(depth, InspectError.NOT_VISIBLE, "target agent is not in your node")
        }

        // Agent passed presence (active position) but has no body row — that's a state
        // inconsistency (a presence write without a paired body upsert), not a normal
        // case. Surface it as NOT_FOUND so the caller has something to react to rather
        // than silently emitting "unknown" bands.
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
        // Inventory-only in Phase 0 — equipment / dropped-item-on-ground are future slices.
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
                gatheringSkill = if (depth == InspectDepth.EXPERT) item.gatheringSkill else null,
            ),
        )
    }

    private fun projectAgent(target: Agent, body: BodyView, depth: InspectDepth): AgentInspectView {
        val classId = if (depth != InspectDepth.SHALLOW) target.classId?.name else null
        val hpBand = if (depth != InspectDepth.SHALLOW) bandOf(body.hp, body.maxHp) else null
        val staminaBand = if (depth != InspectDepth.SHALLOW) bandOf(body.stamina, body.maxStamina) else null
        // Mana is psionic-only — non-psionic classes have maxMana == 0 and we hide the
        // pool entirely (canon: `Agent.mana` is null for non-psionic).
        val manaBand = if (depth != InspectDepth.SHALLOW && body.maxMana > 0) {
            bandOf(body.mana, body.maxMana)
        } else null
        val activeEffects = if (depth == InspectDepth.EXPERT) {
            // Status effects (Bleed/Burn/Stun/Poison) land in Phase 2 combat. Until then
            // the field is an empty list at EXPERT, matching get_status.
            emptyList<String>()
        } else null
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

    private fun bandOf(current: Int, max: Int): String = when {
        max <= 0 -> "unknown"
        current <= 0 -> "dead"
        current * 10 < max * 3 -> "low"   // < 30%
        current * 10 < max * 7 -> "mid"   // 30-70%
        else -> "high"                     // > 70%
    }

    private fun errorResponse(depth: InspectDepth, code: String, message: String): InspectResponse =
        InspectResponse(
            kind = "error",
            depth = depth.name.lowercase(),
            error = InspectError(code = code, message = message),
        )
}
