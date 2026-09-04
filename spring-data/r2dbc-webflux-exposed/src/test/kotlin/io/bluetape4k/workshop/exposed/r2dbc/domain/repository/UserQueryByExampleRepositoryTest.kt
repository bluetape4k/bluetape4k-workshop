package io.bluetape4k.workshop.exposed.r2dbc.domain.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.exposed.r2dbc.AbstractWebfluxR2dbcExposedApplicationTest
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.service.UserService
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class UserQueryByExampleRepositoryTest(
    @param:Autowired private val repository: UserQueryByExampleRepository,
    @param:Autowired private val service: UserService,
    @param:Autowired private val database: R2dbcDatabase,
) : AbstractWebfluxR2dbcExposedApplicationTest() {

    @Test
    fun `QBE matcher supports sorted cold Flow count and exists`() = runSuspendIO {
        val marker = "qbe-${System.nanoTime()}"
        service.addUser(user(marker, "alice"))
        service.addUser(user(marker, "bob"))

        val example = loginPrefixExample(marker)
        val flow = repository.findAll(example, Sort.by(Sort.Direction.ASC, "name"))

        suspendTransaction(db = database) {
            flow.toList().map(UserRecord::login) shouldBeEqualTo listOf("$marker-alice", "$marker-bob")
            flow.toList().map(UserRecord::login) shouldBeEqualTo listOf("$marker-alice", "$marker-bob")
        }
        repository.count(example) shouldBeEqualTo 2L
        repository.exists(example).shouldBeTrue()
    }

    @Test
    fun `FluentQuery projects selected properties and page metadata`() = runSuspendIO {
        val marker = "qbe-page-${System.nanoTime()}"
        service.addUser(user(marker, "alice"))
        service.addUser(user(marker, "bob"))

        val page = repository.findBy(loginPrefixExample(marker)) { query ->
            query
                .asType(NameLoginProjection::class)
                .project("name", "login")
                .sortBy(Sort.by(Sort.Direction.ASC, "name"))
                .page(PageRequest.of(0, 1))
        }

        page.content shouldHaveSize 1
        page.totalElements shouldBeEqualTo 2L
        page.content.single().login shouldBeEqualTo "$marker-alice"
    }

    @Test
    fun `cold Flow cancellation releases connection for follow-up QBE`() = runSuspendIO {
        val marker = "qbe-cancel-${System.nanoTime()}"
        service.addUser(user(marker, "alice"))
        service.addUser(user(marker, "bob"))

        val example = loginPrefixExample(marker)
        suspendTransaction(db = database) {
            val first = repository.findAll(example).take(1).toList()
            first.shouldNotBeEmpty()
        }

        repository.exists(example).shouldBeTrue()
    }

    private fun user(marker: String, name: String) = UserRecord(
        name = name,
        login = "$marker-$name",
        email = "$marker-$name@example.com",
        avatar = null,
    )

    private fun loginPrefixExample(marker: String): Example<UserRecord> = Example.of(
        UserRecord(name = "", login = marker, email = "", avatar = null, id = -1),
        ExampleMatcher.matching()
            .withIgnorePaths("id", "name", "email", "avatar")
            .withMatcher("login", ExampleMatcher.GenericPropertyMatchers.startsWith()),
    )

    private interface NameLoginProjection {
        val name: String
        val login: String
    }
}
