package dev.gvart.genesara.api.internal.mcp.session

import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.EntityResponse
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRecoveryRouterFilterTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val filter = SessionRecoveryRouterFilter(mapper)

    @Test
    fun `session-not-found 404 is rewritten to structured session_expired JSON-RPC error`() {
        val request = postRequest(
            body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_status"}}""",
            sessionId = "stale-session-id",
        )

        val response = filter.filter(request, sessionNotFoundHandler("stale-session-id"))

        assertEquals(HttpStatus.OK, response.statusCode())
        val entity = (response as EntityResponse<*>).entity()
        @Suppress("UNCHECKED_CAST")
        val envelope = entity as Map<String, Any?>
        assertEquals("2.0", envelope["jsonrpc"])
        assertEquals(7L, envelope["id"])
        @Suppress("UNCHECKED_CAST")
        val error = envelope["error"] as Map<String, Any?>
        assertEquals(-32001, error["code"])
        assertEquals("Session expired, please reinitialize", error["message"])
        @Suppress("UNCHECKED_CAST")
        val data = error["data"] as Map<String, Any?>
        assertEquals("session_expired", data["kind"])
        assertEquals("stale-session-id", data["sessionId"])
    }

    @Test
    fun `string id is preserved verbatim in the rewritten envelope`() {
        val request = postRequest(
            body = """{"jsonrpc":"2.0","id":"req-42","method":"tools/call"}""",
            sessionId = "stale",
        )

        val response = filter.filter(request, sessionNotFoundHandler("stale"))

        val envelope = (response as EntityResponse<*>).entity() as Map<*, *>
        assertEquals("req-42", envelope["id"])
    }

    @Test
    fun `missing id field becomes null id in the rewritten envelope`() {
        val request = postRequest(
            body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            sessionId = "stale",
        )

        val response = filter.filter(request, sessionNotFoundHandler("stale"))

        val envelope = (response as EntityResponse<*>).entity() as Map<*, *>
        assertNull(envelope["id"])
    }

    @Test
    fun `successful response is forwarded unchanged`() {
        val request = postRequest(body = """{"jsonrpc":"2.0","id":1}""", sessionId = "live")
        val passthrough = ServerResponse.accepted().build()

        val response = filter.filter(request) { passthrough }

        assertEquals(passthrough, response)
    }

    @Test
    fun `404 unrelated to session lookup is forwarded unchanged`() {
        val request = postRequest(body = """{"jsonrpc":"2.0","id":1}""", sessionId = "x")
        val other = ServerResponse.status(HttpStatus.NOT_FOUND)
            .body(McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message("Some other 404").build())

        val response = filter.filter(request) { other }

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode())
        val entity = (response as EntityResponse<*>).entity()
        assertTrue(entity is McpError)
    }

    @Test
    fun `GET request bypasses the filter`() {
        val mock = MockHttpServletRequest("GET", "/mcp").apply {
            addHeader("mcp-session-id", "x")
        }
        val request = ServerRequest.create(mock, listOf(StringHttpMessageConverter()))
        val notFound = ServerResponse.status(HttpStatus.NOT_FOUND)
            .body(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message("Session not found: x").build())

        val response = filter.filter(request) { notFound }

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode())
    }

    @Test
    fun `unreadable body still falls through to handler`() {
        val mock = MockHttpServletRequest("POST", "/mcp").apply {
            addHeader("mcp-session-id", "x")
        }
        val request = ServerRequest.create(mock, listOf(StringHttpMessageConverter()))
        val passthrough = ServerResponse.accepted().build()

        val response = filter.filter(request) { passthrough }

        assertNotNull(response)
    }

    private fun postRequest(body: String, sessionId: String): ServerRequest {
        val mock = MockHttpServletRequest("POST", "/mcp").apply {
            contentType = MediaType.APPLICATION_JSON_VALUE
            addHeader("Accept", "application/json, text/event-stream")
            addHeader("mcp-session-id", sessionId)
            setContent(body.toByteArray(Charsets.UTF_8))
        }
        return ServerRequest.create(mock, listOf(StringHttpMessageConverter()))
    }

    private fun sessionNotFoundHandler(sessionId: String): HandlerFunction<ServerResponse> = HandlerFunction { _ ->
        ServerResponse.status(HttpStatus.NOT_FOUND)
            .body(
                McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                    .message("Session not found: $sessionId")
                    .build(),
            )
    }
}
