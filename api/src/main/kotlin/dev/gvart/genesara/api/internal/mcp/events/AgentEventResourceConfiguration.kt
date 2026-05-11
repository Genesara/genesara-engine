package dev.gvart.genesara.api.internal.mcp.events

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification
import io.modelcontextprotocol.spec.McpSchema.Resource
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class AgentEventResourceConfiguration {

    @Bean
    fun agentEventResourceTemplates(handler: AgentEventResource): List<SyncResourceTemplateSpecification> =
        listOf(
            SyncResourceTemplateSpecification(
                ResourceTemplate(
                    "agent://{agentId}/events",
                    "agent-events",
                    "Per-agent event log: pending events since the last read.",
                    "application/json",
                    null,
                ),
                handler::read,
            ),
            SyncResourceTemplateSpecification(
                ResourceTemplate(
                    "agent://{agentId}/events?after={after}",
                    "agent-events-after",
                    "Per-agent event log resumed after a known sequence number.",
                    "application/json",
                    null,
                ),
                handler::read,
            ),
        )

    /** Surfaces the event-stream as a concrete resource so `resources/list` (and clients
     *  like `ListMcpResourcesTool`) can discover it. The `self` literal resolves to the
     *  calling agent at read time; the agent does not need to know its own UUID. Resume
     *  paging is done via the templated form `agent://self/events?after={seq}`. */
    @Bean
    fun agentEventSelfResource(handler: AgentEventResource): List<SyncResourceSpecification> =
        listOf(
            SyncResourceSpecification(
                Resource.builder()
                    .uri(AgentEventResource.SELF_URI)
                    .name("agent-events")
                    .description(
                        "Your event log. Read with no cursor to drain pending events; " +
                            "resume after a known sequence number via `agent://self/events?after={seq}`.",
                    )
                    .mimeType("application/json")
                    .build(),
                handler::read,
            ),
        )
}
