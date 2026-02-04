package io.bluetape4k.workshop.exposed.r2dbc.handler

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.r2dbc.AbstractWebfluxR2dbcExposedApplicationTest
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.service.UserService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.bodyToMono
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class UserHandlerTest: AbstractWebfluxR2dbcExposedApplicationTest() {

    companion object: KLoggingChannel()

    private val service = mockk<UserService>(relaxUnitFun = true)
    private val request = mockk<ServerRequest>(relaxUnitFun = true)

    private val handler = UserHandler(service)

    @BeforeEach
    fun beforeEach() {
        clearMocks(service, request)
    }

    @Test
    fun `find all users`() = runTest {
        coEvery { service.findAll() } returns listOf(createUser(id = 1), createUser(id = 2))

        val response = handler.findAll(request)
        response.statusCode() shouldBeEqualTo HttpStatus.OK
    }

    @Test
    fun `when user is not exists, returns empty flow`() = runTest {
        coEvery { service.findAll() } returns emptyList()

        val response = handler.findAll(request)
        response.statusCode() shouldBeEqualTo HttpStatus.OK
    }

    @Test
    fun `find by id return OK`() = runTest {
        coEvery { request.pathVariable("id") } returns "1"
        coEvery { service.findByIdOrNull(1) } returns createUser(1)


        val response = handler.findUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.OK
    }

    @Test
    fun `find by id non exists return NotFound`() = runTest {
        coEvery { request.pathVariable("id") } returns "-1"
        coEvery { service.findByIdOrNull(-1) } returns null

        val response = handler.findUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.NOT_FOUND
    }

    @Test
    fun `when path variable is not numeric returns BadRequest`() = runTest {
        coEvery { request.pathVariable("id") } returns "id"
        coEvery { service.findByIdOrNull(any()) } returns createUser(id = 1)

        val response = handler.findUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.BAD_REQUEST
        coVerify(exactly = 0) { service.findByIdOrNull(any()) }
    }

    @Test
    fun `add new user returns OK`() = runTest {
        coEvery { request.bodyToMono<UserRecord>() } returns createUserRecord().toMono()
        coEvery { service.addUser(any()) } answers {
            firstArg<UserRecord>().copy(id = 999)
        }

        val response = handler.addUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.CREATED
    }

    @Test
    fun `when invalid body to addUser returns BadRequest`() = runTest {
        coEvery { request.bodyToMono<UserRecord>() } returns Mono.empty()

        val response = handler.addUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `when error in saveUser returns InternalServerError`() = runTest {
        coEvery { service.addUser(any()) } returns null
        coEvery { request.bodyToMono<UserRecord>() } returns createUserRecord().toMono()

        val response = handler.addUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.INTERNAL_SERVER_ERROR
    }

    @Test
    fun `when update existing user returns OK`() = runTest {
        coEvery { request.pathVariable("id") } returns "2"
        coEvery { request.bodyToMono<UserRecord>() } returns createUserRecord().toMono()
        coEvery { service.updateUser(2, any()) } answers {
            secondArg<UserRecord>().copy(id = 2)
        }

        val response = handler.updateUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.OK
    }

    @Test
    fun `when provide non numeric id to updateUser returns BadRequest`() = runTest {
        coEvery { request.pathVariable("id") } returns "NOT-NUMERIC"

        val response = handler.updateUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `when empty body to updateUser returns BadRequest`() = runTest {
        coEvery { request.pathVariable("id") } returns "2"
        coEvery { request.bodyToMono<UserRecord>() } returns Mono.empty()

        val response = handler.updateUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `when update non-existing user returns BadRequest`() = runTest {
        coEvery { request.pathVariable("id") } returns "2"
        coEvery { request.bodyToMono<UserRecord>() } returns createUserRecord().toMono()
        coEvery { service.updateUser(2, any()) } returns null

        val response = handler.updateUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.NOT_FOUND
    }

    @Test
    fun `when delete user is success returns NoContent`() = runTest {
        coEvery { request.pathVariable("id") } returns "2"
        coEvery { service.deleteUser(2) } returns true

        val response = handler.deleteUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.OK
    }

    @Test
    fun `when delete non-existing user returns NotFound`() = runTest {
        coEvery { request.pathVariable("id") } returns "999"
        coEvery { service.deleteUser(999) } returns false

        val response = handler.deleteUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.NOT_FOUND
    }

    @Test
    fun `when delete user with invalid id type returns BadRequest`() = runTest {
        coEvery { request.pathVariable("id") } returns "NON-NUMERIC"

        val response = handler.deleteUser(request)
        response.statusCode() shouldBeEqualTo HttpStatus.BAD_REQUEST
    }
}
