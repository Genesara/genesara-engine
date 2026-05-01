package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.UsernameAlreadyExists
import dev.gvart.genesara.world.WorldEditingError
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Maps domain exceptions to RFC 7807 [ProblemDetail] responses. Spring already
 * renders standard binding/validation errors as ProblemDetail when
 * `spring.mvc.problemdetails.enabled=true`, and `ResponseStatusException` is
 * auto-rendered, so this advice only covers project-specific exceptions.
 */
@RestControllerAdvice
internal class GlobalExceptionAdvice {

    /**
     * Renders [ResponseStatusException] as a ProblemDetail body. The Spring Boot autoconfig
     * gated by `spring.mvc.problemdetails.enabled` does the same in production, but standalone
     * MockMvc tests don't load it — so handling it explicitly keeps the wire shape identical
     * in both contexts.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(e: ResponseStatusException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.statusCode.value()), e.reason ?: "")

    @ExceptionHandler(WorldEditingError::class)
    fun worldEditing(e: WorldEditingError): ProblemDetail {
        val (status, detail) = when (e) {
            is WorldEditingError.WorldNotFound -> HttpStatus.NOT_FOUND to "World not found"
            is WorldEditingError.RegionNotFound -> HttpStatus.NOT_FOUND to "Region not found"
            is WorldEditingError.NodeNotInWorld -> HttpStatus.NOT_FOUND to "Node not found in this world"
            is WorldEditingError.GeometryRequired -> HttpStatus.BAD_REQUEST to (e.message ?: "geometry required")
            is WorldEditingError.UnknownRace -> HttpStatus.BAD_REQUEST to "Unknown race"
            is WorldEditingError.StarterNodeNotTraversable ->
                HttpStatus.BAD_REQUEST to "Starter node terrain is not traversable"
        }
        return ProblemDetail.forStatusAndDetail(status, detail)
    }

    @ExceptionHandler(UsernameAlreadyExists::class)
    fun usernameTaken(e: UsernameAlreadyExists): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "Username is already taken")
}