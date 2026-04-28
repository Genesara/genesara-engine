package dev.gvart.genesara.api.internal.mcp.context

import dev.gvart.genesara.player.AgentId

/**
 * Per-request holder for the authenticated [AgentId]. Populated by the MCP Bearer token filter
 * before tool invocation, cleared after. Tools call [current] to know which agent issued the call.
 */
internal object AgentContextHolder {
    private val holder = ThreadLocal<AgentId>()

    fun set(id: AgentId) {
        holder.set(id)
    }

    fun current(): AgentId =
        holder.get() ?: error("No AgentId in context — request was not authenticated by BearerTokenAgentFilter")

    fun clear() {
        holder.remove()
    }
}
