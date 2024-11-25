package io.bluetape4k.workshop.r2dbc.handlers

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.r2dbc.exception.PostNotFoundException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

@RestControllerAdvice
class RestApiExceptionHandler {

    companion object: KLogging()

    @ExceptionHandler(PostNotFoundException::class)
    suspend fun handle(ex: PostNotFoundException, exchange: ServerWebExchange) {
        log.warn(ex) { "Post[${ex.postId}] not found." }
        exchange.response.statusCode = HttpStatus.NOT_FOUND
        exchange.response.setComplete().awaitFirstOrNull()
    }
}
