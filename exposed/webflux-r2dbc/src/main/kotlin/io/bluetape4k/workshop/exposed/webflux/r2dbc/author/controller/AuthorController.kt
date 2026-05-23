package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.controller

import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.service.AuthorService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/authors")
class AuthorController(private val authorService: AuthorService) {

    @GetMapping
    suspend fun findAll(): List<AuthorDTO> = authorService.findAll()

    @GetMapping("/{id}")
    suspend fun findById(@PathVariable id: Long): AuthorDTO = authorService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(@Valid @RequestBody req: CreateAuthorRequest): AuthorDTO =
        authorService.create(req)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: Long,
        @Valid @RequestBody req: CreateAuthorRequest,
    ): AuthorDTO = authorService.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(@PathVariable id: Long) = authorService.delete(id)
}
