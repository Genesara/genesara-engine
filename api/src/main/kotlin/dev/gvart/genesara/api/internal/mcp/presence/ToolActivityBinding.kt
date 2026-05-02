package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.mcp.McpToolUtils

private val log = LoggerFactory.getLogger("dev.gvart.genesara.api.internal.mcp.tools")

internal fun touchActivity(toolContext: ToolContext, activity: AgentActivityTracker, toolName: String) {
    McpToolUtils.getMcpExchange(toolContext)
    val agent = AgentContextHolder.current()
    activity.touch(agent)
    log.info("MCP tool invoked: tool={} agent={}", toolName, agent.id)
}