package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.player.AgentId
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
internal class AgentEventResource(
    private val log: AgentEventLog,
    private val mapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Suppress("UNUSED_PARAMETER")
    fun read(exchange: McpSyncServerExchange, req: ReadResourceRequest): ReadResourceResult {
        val match = URI_PATTERN.matchEntire(req.uri())
            ?: throw IllegalArgumentException("Invalid agent-events URI: ${req.uri()}")
        val uriAgent = AgentId(UUID.fromString(match.groupValues[1]))
        val after = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L

        val authedAgent = AgentContextHolder.current()
        require(uriAgent == authedAgent) {
            "Agent $authedAgent is not allowed to read events of $uriAgent"
        }

        val events = log.since(uriAgent, after)
        logger.debug("resources/read returned {} events for {} after seq {}", events.size, uriAgent, after)
        val json = mapper.writeValueAsString(events)
        return ReadResourceResult(listOf(TextResourceContents(req.uri(), "application/json", json)))
    }

    companion object {
        private val URI_PATTERN = Regex("^agent://([0-9a-fA-F-]{36})/events(?:\\?after=(\\d+))?$")
    }
}
