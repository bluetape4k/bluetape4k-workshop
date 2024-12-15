package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.UserId
import io.bluetape4k.workshop.exposed.domain.toUserDTO
import io.bluetape4k.workshop.exposed.dto.UserCreateRequest
import io.bluetape4k.workshop.exposed.dto.UserCreateResponse
import io.bluetape4k.workshop.exposed.dto.UserDTO
import io.bluetape4k.workshop.exposed.dto.UserUpdateRequest
import io.bluetape4k.workshop.exposed.service.UserService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
) {

    companion object: KLogging()

    @GetMapping
    fun findAllUsers(): List<UserDTO> {
        return userService.findAllUsers().map { it.toUserDTO() }
    }

    // Read User
    @GetMapping("/{id}")
    fun findUserById(
        @PathVariable id: Long,
    ): UserDTO? {
        return userService.findUserById(UserId(id))?.toUserDTO()
        // ?: throw NotFoundException("User not found. userId=$id")
    }

    /**
     * 사용자 정보를 생성합니다. 생성된 사용자의 id 를 반환합니다.
     */
    @PostMapping
    fun create(
        @RequestBody createRequest: UserCreateRequest,
    ): UserCreateResponse {
        val userId = userService.create(createRequest)

        return UserCreateResponse(id = userId.value)
    }

    /**
     * 사용자 정보를 수정합니다. Update 된 사용자의 수를 반환합니다.
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody updateRequest: UserUpdateRequest,
    ): Int {
        return userService.update(UserId(id), updateRequest)
    }

    /*
     * 사용자 정보를 삭제합니다. Delete 된 사용자의 수를 반환합니다.
     */
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): Int {
        return userService.delete(UserId(id))
    }
}
