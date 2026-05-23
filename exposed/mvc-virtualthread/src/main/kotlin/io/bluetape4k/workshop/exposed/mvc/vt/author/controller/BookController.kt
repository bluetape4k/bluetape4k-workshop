package io.bluetape4k.workshop.exposed.mvc.vt.author.controller

import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.vt.author.service.AuthorService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/books")
class BookController(private val authorService: AuthorService) {

    @GetMapping
    fun findAll(): List<BookDTO> = authorService.findAllBooks()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateBookRequest): BookDTO =
        authorService.createBook(req)
}
