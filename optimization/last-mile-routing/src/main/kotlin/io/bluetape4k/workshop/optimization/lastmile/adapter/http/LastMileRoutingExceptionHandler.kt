package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import io.bluetape4k.workshop.optimization.lastmile.application.LastMileProviderUnavailableException
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileFailureCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

internal data class LastMileErrorResponse(val code: String, val message: String)

@RestControllerAdvice
internal class LastMileRoutingExceptionHandler {
    @ExceptionHandler(LastMileProviderUnavailableException::class)
    fun providerUnavailable(exception: LastMileProviderUnavailableException): ResponseEntity<LastMileErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            LastMileErrorResponse(LastMileFailureCode.PROVIDER_UNAVAILABLE.name, exception.message ?: "provider unavailable"),
        )

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(exception: NoSuchElementException): ResponseEntity<LastMileErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(LastMileErrorResponse("NOT_FOUND", exception.message ?: "not found"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidInput(exception: IllegalArgumentException): ResponseEntity<LastMileErrorResponse> =
        ResponseEntity.badRequest().body(LastMileErrorResponse("INVALID_INPUT", exception.message ?: "invalid input"))
}
