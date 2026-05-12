package dev.gvart.genesara.api.internal.mcp.session

import io.modelcontextprotocol.spec.McpError
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.EntityResponse
import org.springframework.web.servlet.function.HandlerFilterFunction
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

/**
 * Intercepts Spring AI MCP's "Session not found" 404 on POST and rewrites it into a
 * structured JSON-RPC error so a client whose `mcp-session-id` was invalidated by a JVM
 * restart can recover instead of seeing `-32603 INTERNAL_ERROR`.
 */
internal class SessionRecoveryRouterFilter(
    private val mapper: ObjectMapper,
) : HandlerFilterFunction<ServerResponse, ServerResponse> {

    override fun filter(request: ServerRequest, next: HandlerFunction<ServerResponse>): ServerResponse {
        if (request.method() != HttpMethod.POST) return next.handle(request)

        val body = runCatching { request.body(String::class.java) }.getOrNull()
            ?: return next.handle(request)
        val cached = ServerRequest.from(request).body(body).build()

        val response = next.handle(cached)
        if (!isSessionNotFound(response)) return response

        val requestId = extractRequestId(body)
        val sessionId = request.headers().firstHeader(MCP_SESSION_ID_HEADER)
        val envelope = sessionExpiredEnvelope(requestId, sessionId)
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(envelope)
    }

    private fun isSessionNotFound(response: ServerResponse): Boolean {
        if (response.statusCode() != HttpStatus.NOT_FOUND) return false
        val entity = (response as? EntityResponse<*>)?.entity() ?: return false
        val message = (entity as? McpError)?.jsonRpcError?.message() ?: return false
        return message.startsWith(SESSION_NOT_FOUND_PREFIX)
    }

    private fun extractRequestId(body: String): Any? =
        runCatching { mapper.readTree(body).get("id") }
            .getOrNull()
            ?.let { node ->
                when {
                    node.isNumber -> node.asLong()
                    node.isString -> node.asString()
                    else -> null
                }
            }

    private fun sessionExpiredEnvelope(requestId: Any?, sessionId: String?): Map<String, Any?> = mapOf(
        "jsonrpc" to "2.0",
        "id" to requestId,
        "error" to mapOf(
            "code" to SESSION_EXPIRED_CODE,
            "message" to SESSION_EXPIRED_MESSAGE,
            "data" to buildMap<String, Any?> {
                put("kind", "session_expired")
                if (sessionId != null) put("sessionId", sessionId)
            },
        ),
    )

    private companion object {
        const val MCP_SESSION_ID_HEADER = "mcp-session-id"
        const val SESSION_NOT_FOUND_PREFIX = "Session not found:"
        const val SESSION_EXPIRED_MESSAGE = "Session expired, please reinitialize"

        // JSON-RPC server-error range (-32000..-32099 is reserved for "Server error" by JSON-RPC 2.0).
        const val SESSION_EXPIRED_CODE = -32001
    }
}
