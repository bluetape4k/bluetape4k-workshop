package io.bluetape4k.workshop.aws.observability

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/aws-observability")
class OrderTelemetryController(
    private val service: OrderTelemetryService,
) {

    @PostMapping("/orders")
    suspend fun recordOrder(@RequestBody request: OrderTelemetryRequest): OrderTelemetryReport =
        service.recordOrder(request)

    @GetMapping("/metadata")
    suspend fun metadata(): MetadataSnapshot =
        service.readMetadata()
}

@RestControllerAdvice
class OrderTelemetryExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleIllegalArgumentException(e: IllegalArgumentException): TelemetryErrorResponse =
        TelemetryErrorResponse(e.message ?: "invalid request")
}
