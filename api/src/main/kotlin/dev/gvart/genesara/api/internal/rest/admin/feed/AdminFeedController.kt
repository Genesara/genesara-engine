package dev.gvart.genesara.api.internal.rest.admin.feed

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/admin/feed")
internal class AdminFeedController(
    private val log: AdminFeedLog,
    private val broker: AdminFeedBroker,
) {

    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @RequestParam(name = "after", defaultValue = "0") after: Long,
        @RequestParam(name = "type", required = false) type: String?,
        @RequestParam(name = "agent", required = false) agent: String?,
        @RequestParam(name = "node", required = false) node: Long?,
        request: HttpServletRequest,
    ): SseEmitter {
        if (after < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "after must be >= 0")
        val filter = buildFilter(type, agent, node)
        val token = bearerToken(request)
        return broker.register(token, after, filter, STREAM_TIMEOUT.toMillis())
    }

    @GetMapping("/range")
    fun range(
        @RequestParam(name = "from") from: Long,
        @RequestParam(name = "to") to: Long,
        @RequestParam(name = "type", required = false) type: String?,
        @RequestParam(name = "agent", required = false) agent: String?,
        @RequestParam(name = "node", required = false) node: Long?,
    ): List<AdminFeedEvent> {
        if (from < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be >= 0")
        if (to < from) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be >= from")
        return log.range(from, to, buildFilter(type, agent, node))
    }

    private fun buildFilter(type: String?, agent: String?, node: Long?): AdminFeedFilter {
        val types = type?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val agentUuid = agent?.let {
            runCatching { UUID.fromString(it) }
                .getOrElse {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "agent must be a UUID")
                }
        }
        return AdminFeedFilter(types = types, agent = agentUuid, node = node)
    }

    private fun bearerToken(request: HttpServletRequest): String {
        val header = request.getHeader("Authorization")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing Authorization header")
        if (!header.startsWith(BEARER_PREFIX)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "expected Bearer token")
        }
        return header.substring(BEARER_PREFIX.length).trim()
    }

    private companion object {
        val STREAM_TIMEOUT: Duration = Duration.ofMinutes(30)
        const val BEARER_PREFIX = "Bearer "
    }
}
