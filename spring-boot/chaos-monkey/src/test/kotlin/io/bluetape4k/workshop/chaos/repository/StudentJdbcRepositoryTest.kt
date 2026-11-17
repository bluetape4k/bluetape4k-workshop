package io.bluetape4k.workshop.chaos.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.chaos.model.Student
import io.bluetape4k.workshop.chaos.model.StudentTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class StudentJdbcRepositoryTest {

    private lateinit var database: Database
    private lateinit var repository: StudentJdbcRepository

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:student-repository-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.create(StudentTable)
        }
        repository = StudentJdbcRepository(database)
    }

    @AfterEach
    fun tearDown() {
        try {
            transaction(database) {
                SchemaUtils.drop(StudentTable)
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    @Test
    fun `CRUD preserves student contract`() {
        repository.insert(Student(10003, "Ada", "P1234567")) shouldBeEqualTo 1

        val inserted = repository.findById(10003).shouldNotBeNull()
        inserted.name shouldBeEqualTo "Ada"
        inserted.passportNumber shouldBeEqualTo "P1234567"

        repository.update(Student(10003, "Grace", "P7654321")) shouldBeEqualTo 1

        val updated = repository.findById(10003).shouldNotBeNull()
        updated.name shouldBeEqualTo "Grace"
        updated.passportNumber shouldBeEqualTo "P7654321"

        repository.deleteById(10003) shouldBeEqualTo 1
        repository.findById(10003).shouldBeNull()
    }

    @Test
    fun `validation preserves positive id and non blank field contracts`() {
        assertFailsWith<IllegalArgumentException> { repository.findById(0) }
        assertFailsWith<IllegalArgumentException> { repository.deleteById(0) }
        assertFailsWith<IllegalArgumentException> { repository.insert(Student(10004, " ", "P1234567")) }
        assertFailsWith<IllegalArgumentException> { repository.update(Student(10004, "Ada", " ")) }
    }
}
