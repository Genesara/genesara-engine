package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.mcp.McpToolUtils

internal fun touchActivity(toolContext: ToolContext, activity: AgentActivityTracker) {
    McpToolUtils.getMcpExchange(toolContext)
    activity.touch(AgentContextHolder.current())
}
