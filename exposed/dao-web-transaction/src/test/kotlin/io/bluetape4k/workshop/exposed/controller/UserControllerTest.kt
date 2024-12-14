package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpDelete
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.spring.tests.httpPut
import io.bluetape4k.workshop.exposed.AbstractExposedApplicationTest
import io.bluetape4k.workshop.exposed.dto.UserCreateResponse
import io.bluetape4k.workshop.exposed.dto.UserDTO
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import org.springframework.transaction.annotation.Transactional

@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractExposedApplicationTest() {

    companion object: KLogging() {
        private const val newUserSize = 100
    }

    @Test
    @Order(0)
    fun `create user`() = runTest {
        val createRequest = newUserCreateRequest()
        log.debug { "Create user. request=$createRequest" }

        val response = client.httpPost("/api/v1/users", createRequest)
            .returnResult<UserCreateResponse>()
            .responseBody
            .awaitSingle()

        log.debug { "Create user. userId=${response.id}" }
        response.id shouldBeGreaterThan 0
    }

    @Test
    @Order(1)
    fun `create multiple users`() = runTest {
        val jobs = List(newUserSize) {
            launch {
                val createRequest = newUserCreateRequest()
                log.debug { "Create user. request=$createRequest" }

                val response = client.httpPost("/api/v1/users", createRequest)
                    .returnResult<UserCreateResponse>()
                    .responseBody
                    .awaitSingle()

                log.debug { "Create user. userId=${response.id}" }
                response.id shouldBeGreaterThan 0
            }
        }
        jobs.joinAll()
    }

    @Test
    @Order(2)
    fun `update user`() = runTest {
        val createRequest = newUserCreateRequest()
        val userId = client.httpPost("/api/v1/users", createRequest)
            .returnResult<UserCreateResponse>()
            .responseBody
            .awaitSingle()
            .id

        userId shouldBeGreaterThan 0

        val updateRequest = newUserUpdateRequest()
        log.debug { "Update user. userId=$userId, request=$updateRequest" }

        val response = client.httpPut("/api/v1/users/$userId", updateRequest)
            .returnResult<Int>()
            .responseBody
            .awaitSingle()

        response shouldBeEqualTo 1
    }

    @Test
    @Order(3)
    fun `delete user`() = runTest {
        val createRequest = newUserCreateRequest()
        val userId = client.httpPost("/api/v1/users", createRequest)
            .returnResult<UserCreateResponse>()
            .responseBody
            .awaitSingle()
            .id

        userId shouldBeGreaterThan 0

        log.debug { "Delete user. userId=$userId" }

        val response = client.httpDelete("/api/v1/users/$userId")
            .returnResult<Int>()
            .responseBody
            .awaitSingle()

        response shouldBeEqualTo 1
    }

    @Test
    @Order(4)
    fun `find user by id`() = runTest {
        val createRequest = newUserCreateRequest()
        val userId = client.httpPost("/api/v1/users", createRequest)
            .returnResult<UserCreateResponse>()
            .responseBody
            .awaitSingle()
            .id

        userId shouldBeGreaterThan 0

        log.debug { "Find user by id. userId=$userId" }

        val response = client.httpGet("/api/v1/users/$userId")
            .returnResult<UserDTO>()
            .responseBody
            .awaitSingle()

        response.id shouldBeEqualTo userId
        response.name shouldBeEqualTo createRequest.name
        response.age shouldBeEqualTo createRequest.age
    }

}
