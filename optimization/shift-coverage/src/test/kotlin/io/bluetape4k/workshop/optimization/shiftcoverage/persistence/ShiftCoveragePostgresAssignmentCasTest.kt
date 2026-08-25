package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftCoveragePostgresAssignmentCasTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = ShiftCoveragePostgresAssignmentRepository()
    private val assignment = ShiftAssignment(AssignmentId("assignment-pg"), SiteId("site-pg"), ShiftId("shift-pg"), WorkerId("worker-a"))

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = requireNotNull(postgres.username),
            password = requireNotNull(postgres.password),
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(ShiftCoverageAssignmentsTable)
            SchemaUtils.create(ShiftCoverageAssignmentsTable)
        }
    }

    @AfterAll
    fun dropSchema() {
        transaction { SchemaUtils.drop(ShiftCoverageAssignmentsTable) }
    }

    @Test
    fun `postgres assignment CAS accepts one revision and rejects stale retry`() {
        repository.saveAssignment(assignment)
        val replacement = assignment.copy(workerId = WorkerId("worker-b"), revision = 1L)

        repository.compareAndSetAssignment(assignment.assignmentId, 0L, replacement).shouldBeTrue()
        repository.compareAndSetAssignment(assignment.assignmentId, 0L, assignment.copy(revision = 1L)).shouldBeFalse()
        repository.findAssignment(assignment.assignmentId)?.workerId shouldBeEqualTo WorkerId("worker-b")
    }
}
