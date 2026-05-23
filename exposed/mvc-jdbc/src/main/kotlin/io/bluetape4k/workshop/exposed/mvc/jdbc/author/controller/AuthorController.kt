package io.bluetape4k.workshop.exposed.mvc.jdbc.author.controller

import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.service.AuthorService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/authors")
class AuthorController(private val authorService: AuthorService) {

    @GetMapping
    fun findAll(): List<AuthorDTO> = authorService.findAll()

    @GetMapping("/{id}")
    fun findById(@PathVariable id: Long): AuthorDTO = authorService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateAuthorRequest): AuthorDTO =
        authorService.createAuthor(req)

    @GetMapping("/{id}/books")
    fun findBooks(@PathVariable id: Long): List<BookDTO> = authorService.findBooksBy(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = authorService.deleteAuthor(id)
}
