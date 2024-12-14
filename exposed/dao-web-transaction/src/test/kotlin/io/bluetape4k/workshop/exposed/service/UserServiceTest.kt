package io.bluetape4k.workshop.exposed.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedApplicationTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserServiceTest(
    @Autowired private val userService: UserService,
): AbstractExposedApplicationTest() {

    companion object: KLogging() {
        private const val newUserSize = 100
    }

    @Test
    @Order(0)
    fun `create user`() {
        val userId = userService.create(newUserCreateRequest())
        log.debug { "Create user. userId=${userId.value}" }
    }

    @Test
    @Order(1)
    fun `create multiple users`() {
        repeat(newUserSize) {
            val userId = userService.create(newUserCreateRequest())
            log.debug { "Create user. userId=${userId.value}" }
        }
    }

    @Test
    @Order(2)
    fun `update user`() {
        val createRequest = newUserCreateRequest()

        val userId = userService.create(createRequest)

        val updateRequest = newUserUpdateRequest()
        val affectedRow = userService.update(userId, updateRequest)

        affectedRow shouldBeEqualTo 1

        val updatedUser = userService.findUserById(userId)!!
        updatedUser.name shouldBeEqualTo updateRequest.name
        updatedUser.age shouldBeEqualTo (updateRequest.age ?: createRequest.age)
    }

    @Test
    @Order(3)
    fun `delete user`() {
        val userId = userService.create(newUserCreateRequest())

        val affectedRow = userService.delete(userId)
        affectedRow shouldBeEqualTo 1
    }

    @Test
    @Order(4)
    fun `find by user id`() {
        val userRequest = newUserCreateRequest()
        val userId = userService.create(userRequest)

        val user = userService.findUserById(userId)!!

        user.name shouldBeEqualTo userRequest.name
        user.age shouldBeEqualTo userRequest.age
    }
}
