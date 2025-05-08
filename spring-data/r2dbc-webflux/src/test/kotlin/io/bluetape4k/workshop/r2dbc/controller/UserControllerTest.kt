package io.bluetape4k.workshop.r2dbc.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpDelete
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.spring.tests.httpPut
import io.bluetape4k.workshop.r2dbc.AbstractWebfluxR2dbcApplicationTest
import io.bluetape4k.workshop.r2dbc.domain.User
import io.bluetape4k.workshop.r2dbc.domain.toDto
import io.bluetape4k.workshop.r2dbc.service.UserService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class UserControllerTest(
    @Autowired private val client: WebTestClient,
    @Autowired private val service: UserService,
): AbstractWebfluxR2dbcApplicationTest() {

    companion object: KLogging()

    @Nested
    inner class Find {
        @Test
        fun `find all users as Flow`() = runTest {
            val users = client.httpGet("/api/users")
                .returnResult<User>().responseBody
                .asFlow()
                .toList()

            users.shouldNotBeEmpty()
            users.forEach { log.debug { it } }
        }

        @Test
        fun `find by id - existing user`() = runTest {
            val user = client.httpGet("/api/users/1")
                .returnResult<User>().responseBody
                .awaitSingle()

            log.debug { "Find by id[1] = $user" }
            user.shouldNotBeNull()
        }

        @Test
        fun `find by id - non-existing user`() = runTest {
            val message = client
                .httpGet("/api/users/9999", HttpStatus.NOT_FOUND)
                .returnResult<String>().responseBody
                .awaitSingle()

            log.debug { message }
            message.shouldNotBeNull() shouldContain "Not Found"
        }

        @Test
        fun `find by id - non-numeric id`() = runTest {
            val message = client
                .httpGet("/api/users/abc", HttpStatus.BAD_REQUEST)
                .returnResult<String>().responseBody
                .awaitSingle()

            log.debug { "message=$message" }
            message.shouldNotBeNull() shouldContain "Bad Request"
        }
    }

    @Nested
    inner class Search {
        @Test
        fun `search by valid email returns Users`() = runTest {
            val searchEmail = "user2@users.com"

            val searchedUsers = client
                .httpGet("/api/users/search?email=$searchEmail")
                .returnResult<User>().responseBody
                .asFlow()
                .toList()

            searchedUsers shouldHaveSize 1
            searchedUsers.all { it.email == searchEmail }.shouldBeTrue()
        }

        @Test
        fun `search by empty email returns Users`() = runTest {
            val searchEmail = ""

            client.httpGet("/api/users/search?email=$searchEmail", HttpStatus.BAD_REQUEST)
        }

        @Test
        fun `search without email returns Users`() = runTest {
            client.httpGet("/api/users/search", HttpStatus.BAD_REQUEST)
        }
    }

    @Nested
    inner class Add {
        @Test
        fun `add new user`() = runTest {
            val newUser = createUserDTO()

            val savedUser = client
                .httpPost("/api/users", newUser)
                .returnResult<User>().responseBody
                .awaitSingle()

            savedUser.id.shouldNotBeNull()
            savedUser.toDto() shouldBeEqualTo newUser
        }

        @Test
        fun `add new user with invalid format`() = runTest {
            val newUser = "new user"

            client.httpPost("/api/users", newUser, HttpStatus.UNSUPPORTED_MEDIA_TYPE)
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
                .httpPut("/api/users/${savedUser.id}", userToUpdate)
                .returnResult<User>().responseBody
                .awaitSingle()

            updatedUser.id.shouldNotBeNull()
            updatedUser.toDto() shouldBeEqualTo userToUpdate
        }

        @Test
        fun `update non-existing user`() = runTest {
            val userToUpdate = createUserDTO()
            client.httpPut("/api/users/9999", userToUpdate, HttpStatus.NOT_FOUND)
        }

        @Test
        fun `update user with invalid format`() = runTest {
            val userToUpdate = "new user"
            client.httpPut("/api/users/2", userToUpdate, HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        }

        @Test
        fun `update user with invalid id`() = runTest {
            val userToUpdate = "new user"
            client.httpPut("/api/users/abc", userToUpdate, HttpStatus.BAD_REQUEST)
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun `delete existing user`() = runTest {
            val newUser = createUserDTO()
            val savedUser = service.addUser(newUser)!!

            client
                .httpDelete("/api/users/${savedUser.id}")
                .returnResult<Boolean>().responseBody
                .awaitSingle()
                .shouldBeTrue()
        }

        @Test
        fun `delete non-existing user`() = runTest {
            client.httpDelete("/api/users/9999", HttpStatus.NOT_FOUND)
        }

        @Test
        fun `delete by non-numeric id`() = runTest {
            client.httpDelete("/api/users/abc", HttpStatus.BAD_REQUEST)
        }
    }
}
