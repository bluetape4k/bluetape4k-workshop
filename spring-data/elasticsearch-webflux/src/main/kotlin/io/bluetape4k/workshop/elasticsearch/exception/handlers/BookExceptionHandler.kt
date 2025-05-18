package io.bluetape4k.workshop.elasticsearch.exception.handlers

import io.bluetape4k.workshop.elasticsearch.domain.exception.BookNotFoundException
import io.bluetape4k.workshop.elasticsearch.domain.exception.DuplicatedIsbnException
import kotlinx.coroutines.reactor.mono
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import reactor.core.publisher.Mono
import java.io.Serializable

@RestControllerAdvice
class BookExceptionHandler {

    @ExceptionHandler(value = [BookNotFoundException::class])
    fun doHandleNotFoundException(ex: BookNotFoundException): Mono<ResponseEntity<Any?>> = mono {
        ResponseEntity.notFound().build()
    }

    @ExceptionHandler(value = [DuplicatedIsbnException::class])
    fun doHandleDuplicatedIsbn(ex: DuplicatedIsbnException): Mono<ResponseEntity<Body>> = mono {
        ResponseEntity.badRequest().body(Body(ex.message!!))
    }

    data class Body(
        val message: String,
    ): Serializable
}
