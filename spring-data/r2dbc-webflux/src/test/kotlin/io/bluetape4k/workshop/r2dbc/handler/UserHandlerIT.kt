package io.bluetape4k.workshop.r2dbc.handler

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.r2dbc.AbstractWebfluxR2dbcApplicationTest
import io.bluetape4k.workshop.r2dbc.domain.User
import io.bluetape4k.workshop.r2dbc.domain.UserDTO
import io.bluetape4k.workshop.r2dbc.domain.toDto
import io.bluetape4k.workshop.r2dbc.service.UserService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.returnResult

class UserHandlerIT(
    @param:Autowired private val service: UserService,
): AbstractWebfluxR2dbcApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        webTestClient.shouldNotBeNull()
    }

    @Nested
    inner class Find {
        @Test
        fun `find all users`() = runSuspendIO {
            val users = webTestClient
                .get()
                .uri("/users")
                .exchange()
                .expectStatus().is2xxSuccessful
                .returnResult<UserDTO>().responseBody
                .asFlow().toList()

            users.shouldNotBeEmpty()
            users.forEach { user ->
                log.debug { "findAll. user=$user" }
            }
        }

        @Test
        fun `find by id - exsting user`() = runSuspendIO {
            val user = webTestClient
                .get()
                .uri("/users/1")
                .exchange()
                .expectStatus().is2xxSuccessful
                .returnResult<User>().responseBody
                .awaitSingle()

            user.shouldNotBeNull()
            log.debug { "Find by Id[1] =$user" }
        }

        @Test
        fun `find by id - non-exsting user`() = runSuspendIO {
            webTestClient
                .get()
                .uri("/users/9999")
                .exchange()
                .expectStatus().isNotFound
        }

        @Test
        fun `find by id - invalid user id`() = runSuspendIO {
            webTestClient
                .get()
                .uri("/users/user_id")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }
    }

    @Nested
    inner class Search {
        @Test
        fun `search by email`() = runSuspendIO {
            val searchEmail = "user2@users.com"

            val searchedUsers = webTestClient
                .get()
                .uri("/users/search?email=$searchEmail")
                .exchange()
                .expectStatus().is2xxSuccessful
                .returnResult<User>().responseBody
                .asFlow().toList()

            searchedUsers shouldHaveSize 1
            searchedUsers.all { it.email == searchEmail }.shouldBeTrue()
        }

        @Test
        fun `search by empty email returns BadReqeust`() = runSuspendIO {
            val searchEmail = ""
            webTestClient
                .get()
                .uri("/users/search?email=$searchEmail")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Not provide email to search")
        }

        @Test
        fun `search without params return BadRequest`() = runSuspendIO {
            webTestClient
                .get()
                .uri("/users/search")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Search must have query parameter")
        }
    }

    @Nested
    inner class Add {
        @Test
        fun `add new user`() = runSuspendIO {
            val newUser = createUserDTO()

            val savedUser = webTestClient
                .post()
                .uri("/users")
                .bodyValue(newUser)
                .exchange()
                .expectStatus().isCreated
                .returnResult<User>().responseBody.awaitSingle()

            savedUser.id.shouldNotBeNull()
            savedUser.toDto() shouldBeEqualTo newUser
        }

        @Test
        fun `add new user with bad format`() = runSuspendIO {
            val newUser = "bad format"

            webTestClient
                .post()
                .uri("/users")
                .bodyValue(newUser)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid body")
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `update existing user`() = runSuspendIO {
            val newUser = createUserDTO()
            val savedUser = service.addUser(newUser)!!

            val userToUpdate = createUserDTO()

            val updatedUser = webTestClient
                .put()
                .uri("/users/${savedUser.id}")
                .bodyValue(userToUpdate)
                .exchange()
                .expectStatus().is2xxSuccessful
                .returnResult<User>().responseBody
                .awaitSingle()

            updatedUser.toDto() shouldBeEqualTo userToUpdate
        }

        @Test
        fun `update with non-numeric id`() = runSuspendIO {
            val userToUpdate = createUserDTO()

            webTestClient
                .put()
                .uri("/users/abc")
                .bodyValue(userToUpdate)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }

        @Test
        fun `update with invalid userDTO`() = runSuspendIO {
            val userToUpdate = "bad format"

            webTestClient
                .put()
                .uri("/users/1")
                .bodyValue(userToUpdate)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid body")
        }


        @Test
        fun `update non-existing user`() = runSuspendIO {
            val userToUpdate = createUserDTO()

            webTestClient
                .put()
                .uri("/users/9999")
                .bodyValue(userToUpdate)
                .exchange()
                .expectStatus().isNotFound
                .expectBody()
                .jsonPath("$.message").isEqualTo("User[9999] not found")
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun `delete existing user`() = runSuspendIO {
            val newUser = createUserDTO()
            val savedUser = service.addUser(newUser)!!

            webTestClient
                .delete()
                .uri("/users/${savedUser.id}")
                .exchange()
                .expectStatus().isNoContent
        }

        @Test
        fun `delete non-existing user`() = runSuspendIO {
            webTestClient
                .delete()
                .uri("/users/9999")
                .exchange()
                .expectStatus().isNotFound
                .expectBody()
                .jsonPath("$.message").isEqualTo("User[9999] not found")
        }

        @Test
        fun `delete user with non-numeric id`() = runSuspendIO {
            webTestClient
                .delete()
                .uri("/users/abc")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }
    }
}
