package io.bluetape4k.workshop.exposed.r2dbc.handler

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.r2dbc.AbstractWebfluxR2dbcExposedApplicationTest
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.service.UserService
import io.bluetape4k.workshop.shared.web.httpDelete
import io.bluetape4k.workshop.shared.web.httpGet
import io.bluetape4k.workshop.shared.web.httpPost
import io.bluetape4k.workshop.shared.web.httpPut
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.returnResult

class UserHandlerIT(
    @param:Autowired private val service: UserService,
): AbstractWebfluxR2dbcExposedApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        webTestClient.shouldNotBeNull()
    }

    @Nested
    inner class Find {
        @Test
        fun `find all users`() = runTest {
            val users = webTestClient
                .httpGet("/users")
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .asFlow().toList()

            users.shouldNotBeEmpty()
            users.forEach { user ->
                log.debug { "findAll. user=$user" }
            }
        }

        @Test
        fun `find by id - exsting user`() = runTest {
            val user = webTestClient
                .httpGet("/users/1")
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .awaitSingle()

            user.shouldNotBeNull()
            log.debug { "Find by Id[1] =$user" }
        }

        @Test
        fun `find by id - non-exsting user`() = runTest {
            webTestClient
                .httpGet("/users/9999")
                .expectStatus().isNotFound
        }

        @Test
        fun `find by id - invalid user id`() = runTest {
            webTestClient
                .httpGet("/users/user_id")
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }
    }

    @Nested
    inner class Search {
        @Test
        fun `search by email`() = runTest {
            val searchEmail = "user2@users.com"

            val searchedUsers = webTestClient
                .httpGet("/users/search?email=$searchEmail")
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .asFlow().toList()

            searchedUsers shouldHaveSize 1
            searchedUsers.all { it.email == searchEmail }.shouldBeTrue()
        }

        @Test
        fun `search by empty email returns BadReqeust`() = runTest {
            val searchEmail = ""
            webTestClient
                .httpGet("/users/search?email=$searchEmail")
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Not provide email to search")
        }

        @Test
        fun `search without params return BadRequest`() = runTest {
            webTestClient
                .httpGet("/users/search")
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Search must have query parameter")
        }
    }

    @Nested
    inner class Add {
        @Test
        fun `add new user`() = runTest {
            val newUser = createUserDTO()

            val savedUser = webTestClient
                .httpPost("/users", newUser)
                .expectStatus().isCreated
                .returnResult<UserRecord>().responseBody
                .awaitSingle()

            savedUser.id.shouldNotBeNull()
            savedUser shouldBeEqualTo newUser.copy(id = savedUser.id)
        }

        @Test
        fun `add new user with bad format`() = runTest {
            val newUser = "bad format"

            webTestClient
                .httpPost("/users", newUser)
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid body")
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `update existing user`() = runTest {
            val newUser = createUserDTO()
            val savedUser = service.addUser(newUser)!!

            val userToUpdate = createUserDTO().copy(id = savedUser.id)

            val updatedUser = webTestClient
                .httpPut("/users/${savedUser.id}", userToUpdate)
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .awaitSingle()

            updatedUser shouldBeEqualTo userToUpdate
        }

        @Test
        fun `update with non-numeric id`() = runTest {
            val userToUpdate = createUserDTO()

            webTestClient
                .httpPut("/users/abc", userToUpdate)
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }

        @Test
        fun `update with invalid userDTO`() = runTest {
            val userToUpdate = "bad format"

            webTestClient
                .httpPut("/users/1", userToUpdate)
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid body")
        }


        @Test
        fun `update non-existing user`() = runTest {
            val userToUpdate = createUserDTO()

            webTestClient
                .httpPut("/users/9999", userToUpdate)
                .expectStatus().isNotFound
                .expectBody()
                .jsonPath("$.message").isEqualTo("User[9999] not found")
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun `delete existing user`() = runTest {
            val newUser = createUserDTO()
            val savedUser = service.addUser(newUser)!!

            webTestClient
                .httpDelete("/users/${savedUser.id}")
                .expectStatus().is2xxSuccessful
                .returnResult<Boolean>().responseBody
                .awaitSingle()
                .shouldBeTrue()
        }

        @Test
        fun `delete non-existing user`() = runTest {
            webTestClient
                .httpDelete("/users/9999")
                .expectStatus().isNotFound
                .expectBody()
                .jsonPath("$.message").isEqualTo("User[9999] not found")
        }

        @Test
        fun `delete user with non-numeric id`() = runTest {
            webTestClient
                .httpDelete("/users/abc")
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }
    }
}
