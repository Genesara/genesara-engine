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
        val authedAgent = AgentContextHolder.current()
        val identifier = match.groupValues[1]
        val uriAgent =
            if (identifier == SELF_ALIAS) authedAgent else AgentId(UUID.fromString(identifier))
        val after = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L

        require(uriAgent == authedAgent) {
            "Agent $authedAgent is not allowed to read events of $uriAgent"
        }

        val events = log.since(uriAgent, after)
        logger.debug("resources/read returned {} events for {} after seq {}", events.size, uriAgent, after)
        val json = mapper.writeValueAsString(events)
        return ReadResourceResult(listOf(TextResourceContents(req.uri(), "application/json", json)))
    }

    companion object {
        const val SELF_ALIAS = "self"
        const val SELF_URI = "agent://$SELF_ALIAS/events"
        private val URI_PATTERN = Regex("^agent://($SELF_ALIAS|[0-9a-fA-F-]{36})/events(?:\\?after=(\\d+))?$")
    }
}
