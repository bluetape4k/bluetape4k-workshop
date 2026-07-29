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
 * user CRUD endpoint 를 노출하는 REST controller 입니다.
 *
 * ## Behavior / Contract
 * - `GET /users/{id}` 는 user body 와 함께 200 을 반환하거나 찾지 못하면 404 를 반환합니다.
 * - `POST /users` 는 생성된 user 와 함께 201 Created 를 반환합니다.
 */
@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {
    /**
     * ID 로 user 를 조회합니다. 찾지 못하면 404 를 반환합니다.
     */
    @GetMapping("/{id}")
    suspend fun getUser(@PathVariable id: Long): ResponseEntity<User> {
        val user = userService.getById(id)
        return if (user != null) ResponseEntity.ok(user)
        else ResponseEntity.notFound().build()
    }

    /**
     * 새 user 를 생성합니다. 201 Created 를 반환합니다.
     */
    @PostMapping
    suspend fun createUser(@RequestBody user: User): ResponseEntity<User> {
        val created = userService.create(user)
        return ResponseEntity.created(URI.create("/users/${created.id}")).body(created)
    }
}
