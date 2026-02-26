package io.bluetape4k.workshop.exposed.r2dbc.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.service.UserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping(path = ["/api"])
class UserController(
    private val service: UserService,
): CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {

    companion object: KLoggingChannel()

    @GetMapping("/users")
    suspend fun findAll(): List<UserRecord> {
        return service.findAll()
    }

    @GetMapping("/users/search")
    suspend fun search(@RequestParam(name = "email", required = false) email: String?): List<UserRecord> {
        if (email.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not provide email to search")
        }
        return service.findByEmail(email)
    }

    @GetMapping("/users/{id}")
    suspend fun findUserById(@PathVariable id: Int): UserRecord? {
        return service.findByIdOrNull(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User[$id] not found")
    }

    @PostMapping("/users")
    suspend fun addUser(@RequestBody newUser: UserRecord): UserRecord? {
        return service.addUser(newUser)
    }

    @PutMapping("/users/{id}")
    suspend fun updateUser(@PathVariable id: Int, @RequestBody userToUpdate: UserRecord): UserRecord? {
        return service.updateUser(id, userToUpdate)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User[$id] not found")
    }

    @DeleteMapping("/users/{id}")
    suspend fun deleteUser(@PathVariable id: Int): Boolean {
        val deleted = service.deleteUser(id)
        if (!deleted) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "User[$id] not found")
        }
        return true
    }
}
