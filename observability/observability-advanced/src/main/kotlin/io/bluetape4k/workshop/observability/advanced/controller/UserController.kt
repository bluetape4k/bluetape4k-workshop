package io.bluetape4k.workshop.observability.advanced.controller

import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * REST controller exposing user CRUD endpoints.
 *
 * ## Behavior / Contract
 * - `GET /users/{id}` returns 200 with user body or 404 when not found.
 * - `POST /users` returns 201 Created with the created user.
 */
@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {
    /**
     * Retrieves a user by ID. Returns 404 when not found.
     */
    @GetMapping("/{id}")
    suspend fun getUser(@PathVariable id: Long): ResponseEntity<User> {
        val user = userService.getById(id)
        return if (user != null) ResponseEntity.ok(user)
        else ResponseEntity.notFound().build()
    }

    /**
     * Creates a new user. Returns 201 Created.
     */
    @PostMapping
    suspend fun createUser(@RequestBody user: User): ResponseEntity<User> {
        val created = userService.create(user)
        return ResponseEntity.created(URI.create("/users/${created.id}")).body(created)
    }
}
