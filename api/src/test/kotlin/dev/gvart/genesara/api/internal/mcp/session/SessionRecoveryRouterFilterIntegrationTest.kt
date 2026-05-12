package dev.gvart.genesara.api.internal.mcp.session

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals

class SessionRecoveryRouterFilterIntegrationTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val transport = WebMvcStreamableServerTransportProvider.builder()
        .jsonMapper(JacksonMcpJsonMapper(mapper))
        .mcpEndpoint("/mcp")
        .build()
    private val router = transport.routerFunction.filter(SessionRecoveryRouterFilter(mapper))
    private val mvc = MockMvcBuilders.routerFunctions(router).build()

    @Test
    fun `tools call with stale session id returns structured session_expired envelope, not raw 404`() {
        val response = mvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            header("mcp-session-id", "stale-uuid")
            content = """
                {"jsonrpc":"2.0","id":11,"method":"tools/call",
                 "params":{"name":"get_status","arguments":{}}}
            """.trimIndent()
        }.andReturn().response

        assertEquals(200, response.status)
        val body = mapper.readTree(response.contentAsString)
        assertEquals("2.0", body.get("jsonrpc").asString())
        assertEquals(11L, body.get("id").asLong())
        val error = body.get("error")
        assertEquals(-32001, error.get("code").asInt())
        assertEquals("Session expired, please reinitialize", error.get("message").asString())
        val data = error.get("data")
        assertEquals("session_expired", data.get("kind").asString())
        assertEquals("stale-uuid", data.get("sessionId").asString())
    }

    @Test
    fun `notification with stale session id is acknowledged with 202 and an empty body`() {
        val response = mvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            header("mcp-session-id", "stale-uuid")
            content = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        }.andReturn().response

        assertEquals(202, response.status)
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `POST without mcp-session-id header bypasses the recovery filter and reaches the upstream provider`() {
        val response = mvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"x"}}"""
        }.andReturn().response

        assertEquals(400, response.status)
    }
}
