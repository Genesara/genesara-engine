package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.projection.vitalBand
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * Perception-tier inspect for Tier-A NPCs (Q13b). The detail returned scales
 * with the calling agent's PERCEPTION attribute:
 *
 *  - **shallow** — type, displayName, current node, hpBand.
 *  - **detailed** — adds `aggressionProfile`, `hpCurrent/hpMax` raw, `attackIntervalTicks`.
 *  - **expert** — adds `damage`, `damageType`, `defense`, `dodgeChancePercent`, `range`,
 *    `territoryRadius`, and a preview of the loot table's item ids.
 *
 * NPCs unloaded from the active set (out of R=8 hops from any online agent)
 * read as `not_visible` — agents must close the distance to inspect.
 */
@Component
internal class InspectNpcTool(
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    private val activity: AgentActivityTracker,
) {
    @Tool(
        name = "inspect_npc",
        description = "Inspect a Tier-A NPC visible from your current position. Vision-gated: " +
            "the NPC must sit on a node currently in the active simulation set (typically your " +
            "tile or a few hops away — same set `look_around` lists). Returns shape varies with " +
            "your PERCEPTION attribute (`depth` field): shallow / detailed / expert.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Target NPC — wire-prefixed `npc:<uuid>` as returned by `look_around`.")
        npcId: String,
        toolContext: ToolContext,
    ): NpcInspectResponse {
        touchActivity(toolContext, activity, "inspect_npc")
        val agentId = AgentContextHolder.current()
        val agent = agents.find(agentId) ?: error("Agent not registered: $agentId")
        val depth = inspectDepthFor(agent.attributes.perception)

        val parsedNpcId = PrefixedIds.parseNpc(npcId)
            ?: return NpcInspectResponse.error(depth, "bad_target_id", "npcId must be npc:<uuid>")

        val currentNode = world.locationOf(agentId)
            ?: return NpcInspectResponse.error(depth, "not_in_world", "you are not spawned")

        // We approximate the active-set membership by querying every NPC in the
        // calling agent's vision-radius nodes and matching by id. This avoids a
        // dedicated single-NPC lookup that would bypass the active-set guard.
        val visibleNodes = world.nodesWithin(currentNode, 8)
        val candidates = world.npcsAtNodes(visibleNodes)
        val npc = candidates.values.flatten().firstOrNull { it.id == parsedNpcId }
            ?: return NpcInspectResponse.error(depth, "not_visible", "NPC is not in your visible range")

        val def = world.npcDef(npc.type)
            ?: return NpcInspectResponse.error(depth, "not_found", "NPC type not in catalog: ${npc.type.value}")

        val view = NpcInspectView(
            id = PrefixedIds.encodeNpc(npc.id),
            type = npc.type.value,
            displayName = def.displayName,
            nodeId = npc.nodeId.value,
            hpBand = vitalBand(npc.hpCurrent, npc.hpMax, zeroLabel = "dead"),
            aggressionProfile = if (depth != InspectDepth.SHALLOW) def.aggressionProfile.name else null,
            hpCurrent = if (depth != InspectDepth.SHALLOW) npc.hpCurrent else null,
            hpMax = if (depth != InspectDepth.SHALLOW) npc.hpMax else null,
            attackIntervalTicks = if (depth != InspectDepth.SHALLOW) def.attackIntervalTicks else null,
            damage = if (depth == InspectDepth.EXPERT) def.damage else null,
            damageType = if (depth == InspectDepth.EXPERT) def.damageType else null,
            defense = if (depth == InspectDepth.EXPERT) def.defense else null,
            dodgeChancePercent = if (depth == InspectDepth.EXPERT) def.dodgeChancePercent else null,
            range = if (depth == InspectDepth.EXPERT) def.range else null,
            territoryRadius = if (depth == InspectDepth.EXPERT) def.territoryRadius else null,
        )
        return NpcInspectResponse(
            kind = "npc",
            depth = depth.name.lowercase(),
            npc = view,
        )
    }
}
