package dev.gvart.genesara.api.internal.mcp.tools.getstatus

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetStatusTool(
    private val agents: AgentRegistry,
    private val world: WorldQueryGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_status",
        description = "Return the agent's character snapshot: identity, race, level/XP, attributes, HP/Stamina/Mana, current location, and tick. Read-only — no command queued.",
    )
    fun invoke(req: GetStatusRequest, toolContext: ToolContext): GetStatusResponse {
        touchActivity(toolContext, activity, "get_status")
        val agentId = AgentContextHolder.current()
        val agent = agents.find(agentId) ?: error("Agent not registered: $agentId")

        val body = world.bodyOf(agentId)
        val location = world.activePositionOf(agentId) ?: world.locationOf(agentId)

        return GetStatusResponse(
            agentId = agent.id.id.toString(),
            name = agent.name,
            race = agent.race.value,
            level = agent.level,
            xp = XpView(current = agent.xpCurrent, toNext = agent.xpToNext),
            attributes = AttributesView(
                strength = agent.attributes.strength,
                dexterity = agent.attributes.dexterity,
                constitution = agent.attributes.constitution,
                perception = agent.attributes.perception,
                intelligence = agent.attributes.intelligence,
                luck = agent.attributes.luck,
            ),
            unspentAttributePoints = agent.unspentAttributePoints,
            hp = PoolView(current = body?.hp ?: 0, max = body?.maxHp ?: 0),
            stamina = PoolView(current = body?.stamina ?: 0, max = body?.maxStamina ?: 0),
            mana = PoolView(current = body?.mana ?: 0, max = body?.maxMana ?: 0),
            hunger = PoolView(current = body?.hunger ?: 0, max = body?.maxHunger ?: 0),
            thirst = PoolView(current = body?.thirst ?: 0, max = body?.maxThirst ?: 0),
            sleep = PoolView(current = body?.sleep ?: 0, max = body?.maxSleep ?: 0),
            location = location?.value,
            tick = engine.currentTick(),
        )
    }
}
