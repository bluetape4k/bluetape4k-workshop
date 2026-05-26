package io.bluetape4k.workshop.r2dbc.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.r2dbc.AbstractWebfluxR2dbcApplicationTest
import io.bluetape4k.workshop.r2dbc.domain.toDto
import io.bluetape4k.workshop.r2dbc.repository.UserRepository
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserServiceTest(
    @param:Autowired private val connectionFactory: ConnectionFactory,
    @param:Autowired private val service: UserService,
    @param:Autowired private val repository: UserRepository,
): AbstractWebfluxR2dbcApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    @Order(1)
    fun `context loading`() {
        connectionFactory.shouldNotBeNull()
        service.shouldNotBeNull()
        repository.shouldNotBeNull()
    }

    @Test
    @Order(2)
    fun `find all users`() = runSuspendIO {
        val users = service.findAll().toList()
        users.forEach {
            log.debug { it }
        }
        users.shouldNotBeEmpty()
    }

    @Test
    @Order(3)
    fun `find user by id`() = runSuspendIO {
        val expected = service.findAll().toList().random()

        val actual = service.findById(expected.id!!)
        log.debug { actual }
        actual.shouldNotBeNull() shouldBeEqualTo expected
    }

    @Test
    @Order(4)
    fun `find user by invalid id`() = runSuspendIO {
        val actual = service.findById(-1)
        actual.shouldBeNull()
    }

    @Test
    @Order(5)
    fun `find user by email`() = runSuspendIO {
        val expected = service.findAll().toList().random()

        val actual = service.findByEmail(expected.email).single()
        log.debug { actual }
        actual.shouldNotBeNull() shouldBeEqualTo expected
    }

    @Test
    @Order(6)
    fun `find user by invalid email`() = runSuspendIO {
        val notFounds = service.findByEmail("not-exists@example.com").toList()
        notFounds.shouldBeEmpty()
    }

    @Test
    @Order(7)
    fun `add new user`() = runSuspendIO {
        val newUser = createUserDTO()
        val savedUser = service.addUser(newUser)

        savedUser.shouldNotBeNull()
        savedUser.id.shouldNotBeNull()
        savedUser.toDto() shouldBeEqualTo newUser
    }

    @Test
    @Order(8)
    fun `update existing user`() = runSuspendIO {
        val user = service.findAll().toList().random()

        val updated = service.updateUser(user.id!!, user.copy(avatar = "updated-avatar.jpg").toDto())
        log.debug { "Updated=$updated" }
        updated.shouldNotBeNull()
        updated.id shouldBeEqualTo user.id
        updated.avatar shouldNotBeEqualTo user.avatar

        val saved = service.findByEmail(user.email).first()
        saved.shouldNotBeNull()
        saved.avatar shouldBeEqualTo updated.avatar
    }

    @Test
    @Order(9)
    fun `update non existing user`() = runSuspendIO {
        val nonExists = createUserDTO()
        val actual = service.updateUser(-1, nonExists)
        actual.shouldBeNull()
    }

    @Test
    @Order(10)
    fun `delete existing user`() = runSuspendIO {
        val user = createUserDTO()
        val saved = service.addUser(user)

        saved.shouldNotBeNull()
        saved.id.shouldNotBeNull()

        service.deleteUser(saved.id).shouldBeTrue()
        service.findById(saved.id).shouldBeNull()
    }

    @Test
    @Order(11)
    fun `delete non-existing user`() = runSuspendIO {
        service.deleteUser(-1).shouldBeFalse()
    }
}
