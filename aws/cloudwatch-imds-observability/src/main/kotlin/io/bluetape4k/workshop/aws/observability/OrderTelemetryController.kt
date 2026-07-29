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

/**
 * AWS 관측성 워크숍용 REST 엔드포인트입니다.
 */
@RestController
@RequestMapping("/api/aws-observability")
class OrderTelemetryController(
    private val service: OrderTelemetryService,
) {

    /**
     * 로컬 관측성 파이프라인으로 주문 이벤트를 기록합니다.
     */
    @PostMapping("/orders")
    suspend fun recordOrder(@RequestBody request: OrderTelemetryRequest): OrderTelemetryReport =
        service.recordOrder(request)

    /**
     * 명시적으로 메타데이터를 조회합니다.
     */
    @GetMapping("/metadata")
    suspend fun metadata(): MetadataSnapshot =
        service.readMetadata()
}

/**
 * 검증 실패를 JSON 오류 응답으로 변환합니다.
 */
@RestControllerAdvice
class OrderTelemetryExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleIllegalArgumentException(e: IllegalArgumentException): TelemetryErrorResponse =
        TelemetryErrorResponse(e.message ?: "invalid request")
}
