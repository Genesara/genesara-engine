package dev.gvart.genesara.api.internal.mcp.context

import dev.gvart.genesara.player.AgentId

internal object AgentContextHolder {
    private val holder = ThreadLocal<AgentId>()

    fun set(id: AgentId) {
        holder.set(id)
    }

    fun current(): AgentId =
        holder.get() ?: error("No AgentId in context — request was not authenticated by PlayerApiTokenAgentFilter")

    fun clear() {
        holder.remove()
    }
}
