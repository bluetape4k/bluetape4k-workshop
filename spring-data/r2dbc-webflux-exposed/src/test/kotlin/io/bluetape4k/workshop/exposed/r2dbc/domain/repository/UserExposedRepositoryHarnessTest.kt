package io.bluetape4k.workshop.exposed.r2dbc.domain.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.schema.UserSchema.UserTable
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class UserExposedRepositoryHarnessTest: AbstractExposedR2dbcTest() {

    private fun createUser(testDB: TestDB) = UserRecord(
        name = faker.name().fullName(),
        login = "${testDB.name.lowercase()}-${faker.credentials().username()}",
        email = faker.internet().emailAddress(),
        avatar = faker.avatar().image()
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `repository saves and finds users across enabled r2dbc dialects`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, UserTable) {
            val repository = UserExposedRepository()
            val newUser = createUser(testDB)

            val savedId = repository.save(newUser).value
            val saved = repository.findById(savedId)

            saved shouldBeEqualTo newUser.withId(savedId)
            repository.existsById(savedId).shouldBeTrue()
            repository.findByEmail(newUser.email).toList() shouldHaveSize 1
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `repository updates users across enabled r2dbc dialects`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, UserTable) {
            val repository = UserExposedRepository()
            val savedId = repository.save(createUser(testDB)).value
            val changed = repository.findById(savedId).copy(avatar = "updated-avatar.jpg")

            repository.update(changed) shouldBeEqualTo 1

            repository.findById(savedId) shouldBeEqualTo changed
        }
    }
}
