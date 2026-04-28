package dev.gvart.agenticrpg.api.internal.rest.worlds

import tools.jackson.core.JacksonException
import dev.gvart.agenticrpg.world.WorldEditingError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice(basePackageClasses = [WorldEditorController::class])
internal class EditorErrorAdvice {

    @ExceptionHandler(EditorHttpError::class)
    fun editorHttpError(e: EditorHttpError): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(e.message!!))

    /** Anything from the gateway that escaped the controller's translation. */
    @ExceptionHandler(WorldEditingError::class)
    fun gatewayError(e: WorldEditingError): ResponseEntity<ErrorResponse> {
        val (status, message) = when (e) {
            is WorldEditingError.WorldNotFound -> HttpStatus.NOT_FOUND to "World not found"
            is WorldEditingError.RegionNotFound -> HttpStatus.NOT_FOUND to "Not found"
            is WorldEditingError.GeometryRequired -> HttpStatus.BAD_REQUEST to (e.message ?: "geometry required")
        }
        return ResponseEntity.status(status).body(ErrorResponse(message))
    }

    /** Spring wraps Jackson failures during @RequestBody deserialization. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        val cause = e.mostSpecificCause
        return if (cause is JacksonException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("Invalid JSON body"))
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("Invalid body"))
        }
    }
}

/**
 * Controller-thrown exception that carries an HTTP status and the exact `{error: ...}` message.
 * The advice maps it 1:1 onto the response, so endpoints can pick wording per the contract
 * (e.g. `World not found` vs `Globe node not found`).
 */
internal class EditorHttpError(val status: HttpStatus, message: String) : RuntimeException(message)
