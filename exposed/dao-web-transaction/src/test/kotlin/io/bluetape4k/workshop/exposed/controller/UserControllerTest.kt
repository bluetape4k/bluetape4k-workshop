package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedApplicationTest
import io.bluetape4k.workshop.exposed.dto.UserCreateResponse
import io.bluetape4k.workshop.exposed.dto.UserDTO
import io.bluetape4k.workshop.shared.web.httpDelete
import io.bluetape4k.workshop.shared.web.httpGet
import io.bluetape4k.workshop.shared.web.httpPost
import io.bluetape4k.workshop.shared.web.httpPut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.test.web.reactive.server.expectBodyList
import org.springframework.test.web.reactive.server.returnResult
import org.springframework.transaction.annotation.Transactional

@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserControllerTest: AbstractExposedApplicationTest() {

    companion object: KLogging() {
        private const val newUserSize = 100
    }

    @Test
    @Order(0)
    fun `create user`() = runSuspendIO {
        val createRequest = newUserCreateRequest()
        log.debug { "Create user. request=$createRequest" }

        val response = webTestClient
            .httpPost("/api/v1/users", createRequest)
            .expectStatus().is2xxSuccessful
            .returnResult<UserCreateResponse>().responseBody
            .awaitSingle()

        log.debug { "Create user. userId=${response.id}" }
        response.id.value shouldBeGreaterThan 0
    }

    @Test
    @Order(1)
    fun `create multiple users`() = runSuspendIO {
        val jobs = List(newUserSize) {
            launch(Dispatchers.IO) {
                val createRequest = newUserCreateRequest()
                log.debug { "Create user. request=$createRequest" }

                val response = webTestClient
                    .httpPost("/api/v1/users", createRequest)
                    .expectStatus().is2xxSuccessful
                    .returnResult<UserCreateResponse>().responseBody
                    .awaitSingle()

                log.debug { "Create user. userId=${response.id}" }
                response.id.value shouldBeGreaterThan 0L
            }
        }
        jobs.joinAll()
    }

    @Test
    @Order(2)
    fun `update user`() = runSuspendIO {
        val createRequest = newUserCreateRequest()
        val userId = webTestClient
            .httpPost("/api/v1/users", createRequest)
            .expectStatus().is2xxSuccessful
            .returnResult<UserCreateResponse>().responseBody
            .awaitSingle()
            .id

        userId.value shouldBeGreaterThan 0L

        val updateRequest = newUserUpdateRequest()
        log.debug { "Update user. userId=$userId, request=$updateRequest" }

        val response = webTestClient
            .httpPut("/api/v1/users/${userId.value}", updateRequest)
            .expectStatus().is2xxSuccessful
            .returnResult<Int>().responseBody
            .awaitSingle()

        response shouldBeEqualTo 1
    }

    @Test
    @Order(3)
    fun `delete user`() = runSuspendIO {
        val createRequest = newUserCreateRequest()
        val userId = webTestClient
            .httpPost("/api/v1/users", createRequest)
            .expectStatus().is2xxSuccessful
            .returnResult<UserCreateResponse>().responseBody
            .awaitSingle()
            .id

        userId.value shouldBeGreaterThan 0L

        log.debug { "Delete user. userId=$userId" }

        val response = this@UserControllerTest.webTestClient
            .httpDelete("/api/v1/users/${userId.value}")
            .expectStatus().is2xxSuccessful
            .returnResult<Int>().responseBody
            .awaitSingle()

        response shouldBeEqualTo 1
    }

    @Test
    @Order(4)
    fun `find user by id`() = runSuspendIO {
        val createRequest = newUserCreateRequest()
        val userId = webTestClient
            .httpPost("/api/v1/users", createRequest)
            .expectStatus().is2xxSuccessful
            .returnResult<UserCreateResponse>().responseBody
            .awaitSingle()
            .id

        userId.value shouldBeGreaterThan 0

        log.debug { "Find user by id. userId=$userId" }

        val response = webTestClient
            .httpGet("/api/v1/users/${userId.value}")
            .expectStatus().is2xxSuccessful
            .returnResult<UserDTO>().responseBody
            .awaitSingle()

        response.id shouldBeEqualTo userId.value
        response.name shouldBeEqualTo createRequest.name
        response.age shouldBeEqualTo createRequest.age
    }

    @Test
    @Order(5)
    fun `find all users`() = runSuspendIO {
        repeat(10) {
            val createRequest = newUserCreateRequest()
            webTestClient
                .httpPost("/api/v1/users", createRequest)
                .expectStatus().is2xxSuccessful
                .returnResult<UserCreateResponse>()
                .responseBody
                .awaitSingle()
        }

        val response = webTestClient
            .httpGet("/api/v1/users")
            .expectStatus().is2xxSuccessful
            .expectBodyList<UserDTO>()
            .returnResult().responseBody

        response.shouldNotBeNull().shouldNotBeEmpty()
    }
}
