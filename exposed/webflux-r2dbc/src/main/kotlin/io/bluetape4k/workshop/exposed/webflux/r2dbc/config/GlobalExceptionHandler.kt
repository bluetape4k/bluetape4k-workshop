package io.bluetape4k.workshop.exposed.webflux.r2dbc.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.service.InsufficientStockException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException

@RestControllerAdvice
class GlobalExceptionHandler {

    companion object : KLoggingChannel()

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidation(ex: WebExchangeBindException): ProblemDetail {
        val detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)
        detail.title = "Validation Failed"
        detail.detail = ex.bindingResult.fieldErrors.joinToString("; ") {
            "${it.field}: ${it.defaultMessage}"
        }
        return detail
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ProblemDetail {
        log.warn { "Not found: ${ex.message}" }
        val detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND)
        detail.title = "Not Found"
        detail.detail = ex.message
        return detail
    }

    @ExceptionHandler(InsufficientStockException::class)
    fun handleInsufficientStock(ex: InsufficientStockException): ProblemDetail {
        log.warn { "Insufficient stock: ${ex.message}" }
        val detail = ProblemDetail.forStatus(HttpStatus.CONFLICT)
        detail.title = "Insufficient Stock"
        detail.detail = ex.message
        return detail
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        log.warn { "Bad request: ${ex.message}" }
        val detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)
        detail.title = "Bad Request"
        detail.detail = ex.message
        return detail
    }
}
