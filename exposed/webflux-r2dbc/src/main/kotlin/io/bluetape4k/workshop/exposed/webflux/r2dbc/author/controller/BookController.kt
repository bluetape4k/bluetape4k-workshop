package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.controller

import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookCursorPageResponse
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.service.BookService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.jetbrains.exposed.v1.core.SortOrder

@RestController
@RequestMapping("/api/books")
class BookController(private val bookService: BookService) {

    @GetMapping
    suspend fun findAll(): List<BookDTO> = bookService.findAll()

    @GetMapping("/cursor")
    suspend fun findCursorPage(
        @RequestParam(defaultValue = "20") pageSize: Int,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "ASC") sortOrder: SortOrder,
    ): BookCursorPageResponse = bookService.findCursorPage(pageSize, cursor, sortOrder)

    @GetMapping("/{id}")
    suspend fun findById(@PathVariable id: Long): BookDTO = bookService.findById(id)

    @GetMapping("/author/{authorId}")
    suspend fun findByAuthorId(@PathVariable authorId: Long): List<BookDTO> =
        bookService.findByAuthorId(authorId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(@Valid @RequestBody req: CreateBookRequest): BookDTO =
        bookService.create(req)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: Long,
        @Valid @RequestBody req: CreateBookRequest,
    ): BookDTO = bookService.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(@PathVariable id: Long) = bookService.delete(id)
}
