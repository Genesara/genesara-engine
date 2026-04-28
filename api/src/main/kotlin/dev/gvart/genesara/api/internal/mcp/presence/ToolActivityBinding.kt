package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.mcp.McpToolUtils

/**
 * Marks the calling agent as recently active. Called at the top of every MCP tool method
 * so the presence reaper can distinguish "in the world" from "idle past timeout".
 *
 * The [toolContext] argument is also a hint that the call came in via MCP (rather than a REST
 * mirror); we don't currently use the exchange but reading it here documents the intent.
 */
internal fun touchActivity(toolContext: ToolContext, activity: AgentActivityRegistry) {
    McpToolUtils.getMcpExchange(toolContext)
    activity.touch(AgentContextHolder.current())
}
