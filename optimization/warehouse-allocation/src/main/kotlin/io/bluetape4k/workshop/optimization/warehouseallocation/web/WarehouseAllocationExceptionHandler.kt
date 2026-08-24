package io.bluetape4k.workshop.optimization.warehouseallocation.web

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorDto
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.toErrorDto
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
internal class WarehouseAllocationExceptionHandler {
    @ExceptionHandler(WarehouseAllocationException::class)
    fun warehouse(error: WarehouseAllocationException): ResponseEntity<WarehouseAllocationErrorDto> =
        ResponseEntity.status(status(error)).body(error.toErrorDto("req-${UUID.randomUUID()}"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException): ResponseEntity<WarehouseAllocationErrorDto> =
        ResponseEntity.badRequest().body(WarehouseAllocationErrorDto("INVALID_REQUEST", "req-${UUID.randomUUID()}", false, null, "NO_RETRY"))

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(error: MissingRequestHeaderException): ResponseEntity<WarehouseAllocationErrorDto> {
        val code = if (error.headerName == "X-Request-Id") "INVALID_REQUEST_ID" else "INVALID_REQUEST"
        return ResponseEntity.badRequest().body(WarehouseAllocationErrorDto(code, "req-${UUID.randomUUID()}", false, null, "NO_RETRY"))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(error: HttpMessageNotReadableException): ResponseEntity<WarehouseAllocationErrorDto> =
        ResponseEntity.badRequest().body(WarehouseAllocationErrorDto("INVALID_REQUEST", "req-${UUID.randomUUID()}", false, null, "NO_RETRY"))

    private fun status(error: WarehouseAllocationException): HttpStatusCode = when (error.code) {
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.INVALID_REQUEST,
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.INVALID_REQUEST_ID -> HttpStatus.BAD_REQUEST
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.UNKNOWN_TARGET -> HttpStatus.NOT_FOUND
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.COMMAND_IN_PROGRESS -> HttpStatus.ACCEPTED
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.PLANNER_INPUT_TOO_LARGE,
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.RESPONSE_TOO_LARGE -> HttpStatusCode.valueOf(413)
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.PLANNER_DEADLINE_EXCEEDED,
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.PLANNER_OUTPUT_TOO_LARGE -> HttpStatusCode.valueOf(422)
        io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode.PLANNER_CAPACITY_EXCEEDED -> HttpStatus.SERVICE_UNAVAILABLE
        else -> HttpStatus.CONFLICT
    }
}
