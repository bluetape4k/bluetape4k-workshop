package io.bluetape4k.workshop.r2dbc.handler

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpDelete
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.spring.tests.httpPut
import io.bluetape4k.workshop.r2dbc.AbstractWebfluxR2dbcApplicationTest
import io.bluetape4k.workshop.r2dbc.domain.User
import io.bluetape4k.workshop.r2dbc.domain.UserDTO
import io.bluetape4k.workshop.r2dbc.domain.toDto
import io.bluetape4k.workshop.r2dbc.service.UserService
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
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class UserHandlerIT(
    @Autowired private val client: WebTestClient,
    @Autowired private val service: UserService,
): AbstractWebfluxR2dbcApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Nested
    inner class Find {
        @Test
        fun `find all users`() = runTest {
            val users = client
                .httpGet("/users")
                .returnResult<UserDTO>().responseBody
                .asFlow().toList()

            users.shouldNotBeEmpty()
            users.forEach { user ->
                log.debug { "findAll. user=$user" }
            }
        }

        @Test
        fun `find by id - exsting user`() = runTest {
            val user = client
                .httpGet("/users/1")
                .returnResult<User>().responseBody
                .awaitSingle()

            user.shouldNotBeNull()
            log.debug { "Find by Id[1] =$user" }
        }

        @Test
        fun `find by id - non-exsting user`() = runTest {
            client.httpGet("/users/9999", HttpStatus.NOT_FOUND)
        }

        @Test
        fun `find by id - invalid user id`() = runTest {
            client.httpGet("/users/user_id", HttpStatus.BAD_REQUEST)
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }
    }

    @Nested
    inner class Search {
        @Test
        fun `search by email`() = runTest {
            val searchEmail = "user2@users.com"

            val searchedUsers = client
                .httpGet("/users/search?email=$searchEmail")
                .returnResult<User>().responseBody
                .asFlow().toList()

            searchedUsers shouldHaveSize 1
            searchedUsers.all { it.email == searchEmail }.shouldBeTrue()
        }

        @Test
        fun `search by empty email returns BadReqeust`() = runTest {
            val searchEmail = ""
            client
                .httpGet("/users/search?email=$searchEmail", HttpStatus.BAD_REQUEST)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Not provide email to search")
        }

        @Test
        fun `search without params return BadRequest`() = runTest {
            client
                .httpGet("/users/search", HttpStatus.BAD_REQUEST)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Search must have query parameter")
        }
    }

    @Nested
    inner class Add {
        @Test
        fun `add new user`() = runTest {
            val newUser = createUserDTO()

            val savedUser = client
                .httpPost("/users", newUser, HttpStatus.CREATED)
                .returnResult<User>().responseBody.awaitSingle()

            savedUser.id.shouldNotBeNull()
            savedUser.toDto() shouldBeEqualTo newUser
        }

        @Test
        fun `add new user with bad format`() = runTest {
            val newUser = "bad format"

            client
                .httpPost("/users", newUser, HttpStatus.BAD_REQUEST)
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

            val userToUpdate = createUserDTO()

            val updatedUser = client
                .httpPut("/users/${savedUser.id}", userToUpdate)
                .returnResult<User>().responseBody
                .awaitSingle()

            updatedUser.toDto() shouldBeEqualTo userToUpdate
        }

        @Test
        fun `update with non-numeric id`() = runTest {
            val userToUpdate = createUserDTO()

            client
                .httpPut("/users/abc", userToUpdate, HttpStatus.BAD_REQUEST)
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }

        @Test
        fun `update with invalid userDTO`() = runTest {
            val userToUpdate = "bad format"

            client.httpPut("/users/1", userToUpdate, HttpStatus.BAD_REQUEST)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid body")
        }


        @Test
        fun `update non-existing user`() = runTest {
            val userToUpdate = createUserDTO()

            client.httpPut("/users/9999", userToUpdate, HttpStatus.NOT_FOUND)
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

            client.httpDelete("/users/${savedUser.id}", HttpStatus.NO_CONTENT)
        }

        @Test
        fun `delete non-existing user`() = runTest {
            client.httpDelete("/users/9999", HttpStatus.NOT_FOUND)
                .expectBody()
                .jsonPath("$.message").isEqualTo("User[9999] not found")
        }

        @Test
        fun `delete user with non-numeric id`() = runTest {
            client.httpDelete("/users/abc", HttpStatus.BAD_REQUEST)
                .expectBody()
                .jsonPath("$.message").isEqualTo("`id` must be numeric")
        }
    }
}
