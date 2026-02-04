package io.bluetape4k.workshop.exposed.r2dbc.controller

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
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.returnResult

class UserControllerTest(
    @param:Autowired private val service: UserService,
): AbstractWebfluxR2dbcExposedApplicationTest() {

    companion object: KLoggingChannel()

    @Nested
    inner class Find {
        @Test
        fun `find all users as Flow`() = runTest {
            val users = webTestClient
                .httpGet("/api/users")
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .asFlow()
                .toList()

            users.shouldNotBeEmpty()
            users.forEach { log.debug { it } }
        }

        @Test
        fun `find by id - existing user`() = runTest {
            val user = webTestClient
                .httpGet("/api/users/1")
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .awaitSingle()

            log.debug { "Find by id[1] = $user" }
            user.shouldNotBeNull()
        }

        @Test
        fun `find by id - non-existing user`() = runTest {
            val message = webTestClient
                .httpGet("/api/users/9999")
                .expectStatus().isNotFound
                .returnResult<String>().responseBody
                .awaitSingle()

            log.debug { message }
            message.shouldNotBeNull() shouldContain "Not Found"
        }

        @Test
        fun `find by id - non-numeric id`() = runTest {
            val message = webTestClient
                .httpGet("/api/users/abc")
                .expectStatus().isBadRequest
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

            val searchedUsers = webTestClient
                .httpGet("/api/users/search?email=$searchEmail")
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .asFlow()
                .toList()

            searchedUsers shouldHaveSize 1
            searchedUsers.all { it.email == searchEmail }.shouldBeTrue()
        }

        @Test
        fun `search by empty email returns Users`() = runTest {
            val searchEmail = ""

            webTestClient
                .get()
                .uri("/api/users/search?email=$searchEmail")
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `search without email returns Users`() = runTest {
            webTestClient
                .get()
                .uri("/api/users/search")
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Nested
    inner class Add {
        @Test
        fun `add new user`() = runTest {
            val newUser = createUserRecord()

            val savedUser = webTestClient
                .httpPost("/api/users", newUser)
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .awaitSingle()

            savedUser.id.shouldNotBeNull()
            savedUser shouldBeEqualTo newUser.copy(id = savedUser.id)
        }

        @Test
        fun `add new user with invalid format`() = runTest {
            val newUser = "new user"

            webTestClient
                .httpPost("/api/users", newUser)
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `update existing user`() = runTest {
            val newUser = createUserRecord()
            val savedUser = service.addUser(newUser)!!

            val userToUpdate = createUserRecord().withId(savedUser.id)

            val updatedUser = webTestClient
                .httpPut("/api/users/${savedUser.id}", userToUpdate)
                .expectStatus().is2xxSuccessful
                .returnResult<UserRecord>().responseBody
                .awaitSingle()

            updatedUser.id.shouldNotBeNull()
            updatedUser shouldBeEqualTo userToUpdate
        }

        @Test
        fun `update non-existing user`() = runTest {
            val userToUpdate = createUserRecord()

            webTestClient
                .httpPut("/api/users/9999", userToUpdate)
                .expectStatus().isNotFound
        }

        @Test
        fun `update user with invalid format`() = runTest {
            val userToUpdate = "new user"
            webTestClient
                .httpPut("/api/users/2", userToUpdate)
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        }

        @Test
        fun `update user with invalid id`() = runTest {
            val userToUpdate = "new user"
            webTestClient
                .httpPut("/api/users/abc", userToUpdate)
                .expectStatus().isBadRequest
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun `delete existing user`() = runTest {
            val newUser = createUserRecord()
            val savedUser = service.addUser(newUser)!!

            webTestClient
                .httpDelete("/api/users/${savedUser.id}")
                .expectStatus().is2xxSuccessful
                .returnResult<Boolean>().responseBody
                .awaitSingle()
                .shouldBeTrue()
        }

        @Test
        fun `delete non-existing user`() = runTest {
            webTestClient
                .httpDelete("/api/users/9999")
                .expectStatus().isNotFound
        }

        @Test
        fun `delete by non-numeric id`() = runTest {
            webTestClient
                .httpDelete("/api/users/abc")
                .expectStatus().isBadRequest
        }
    }
}
