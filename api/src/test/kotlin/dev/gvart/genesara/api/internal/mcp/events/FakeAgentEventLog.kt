package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class FakeAgentEventLog : AgentEventLog {
    private val store = ConcurrentHashMap<AgentId, MutableList<AgentEvent>>()
    private val seq = ConcurrentHashMap<AgentId, AtomicLong>()

    override fun append(agent: AgentId, type: String, tick: Long, payload: JsonNode): AgentEvent {
        val s = seq.computeIfAbsent(agent) { AtomicLong(0L) }.incrementAndGet()
        val event = AgentEvent(id = UUID.randomUUID(), seq = s, type = type, tick = tick, payload = payload)
        store.computeIfAbsent(agent) { mutableListOf() }.add(event)
        return event
    }

    override fun since(agent: AgentId, after: Long): List<AgentEvent> =
        store[agent].orEmpty().filter { it.seq > after }.toList()

    override fun discard(agent: AgentId) {
        store.remove(agent)
        seq.remove(agent)
    }
}
