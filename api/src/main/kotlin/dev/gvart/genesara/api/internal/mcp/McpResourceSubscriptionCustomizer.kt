package dev.gvart.genesara.api.internal.mcp

import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Component
@Primary
internal class McpResourceSubscriptionCustomizer : McpSyncServerCustomizer {

    override fun customize(serverBuilder: McpServer.SyncSpecification<*>) {
        serverBuilder.capabilities(
            McpSchema.ServerCapabilities.builder()
                .tools(true)
                .resources(true, false)
                .build(),
        )
        serverBuilder.immediateExecution(true)
    }
}
