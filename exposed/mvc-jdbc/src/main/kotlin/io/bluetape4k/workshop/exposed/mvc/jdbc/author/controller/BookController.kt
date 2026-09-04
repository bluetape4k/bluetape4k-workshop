package io.bluetape4k.workshop.exposed.mvc.jdbc.author.controller

import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookCursorPageResponse
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.service.AuthorService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.jetbrains.exposed.v1.core.SortOrder

@RestController
@RequestMapping("/api/v1/books")
class BookController(private val authorService: AuthorService) {

    @GetMapping
    fun findAll(): List<BookDTO> = authorService.findAllBooks()

    @GetMapping("/cursor")
    fun findCursorPage(
        @RequestParam(defaultValue = "20") pageSize: Int,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "ASC") sortOrder: SortOrder,
    ): BookCursorPageResponse = authorService.findBooksCursor(pageSize, cursor, sortOrder)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateBookRequest): BookDTO =
        authorService.createBook(req)
}
