package dev.gvart.genesara.api.internal.mcp.events

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification
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
        )
}
