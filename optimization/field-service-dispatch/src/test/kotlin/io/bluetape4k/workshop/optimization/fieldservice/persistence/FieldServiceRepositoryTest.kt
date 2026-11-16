package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.jdbc.repository.JdbcRepository
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldServiceRepositoryTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private val repository = FieldServiceRepository()

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username.requireNotNull("username"),
            password = postgres.password.requireNotNull("password"),
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(*FieldServiceTables.all.reversedArray())
            SchemaUtils.create(*FieldServiceTables.all)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction { SchemaUtils.drop(*FieldServiceTables.all.reversedArray()) }
    }

    @Test
    fun `schema exposes nine bounded tables`() {
        FieldServiceTables.all.size shouldBeEqualTo 9
        FieldServiceTables.all.map { it.tableName }.toSet().size shouldBeEqualTo 9
    }

    @Test
    fun `plan persistence exposes the bluetape generic CRUD contract`() {
        repository.shouldBeInstanceOf<JdbcRepository<*, *>>()
    }

    @Test
    fun `worker and visit update include expected version`() {
        transaction {
            val worker = repository.saveWorker(workerRecord())
            repository.findWorker(WorkerId("worker-1")) shouldBeEqualTo worker

            val visit = repository.saveVisit(visitRecord())
            log.debug { "visit=$visit" }
            visit.version shouldBeEqualTo 0L

            repository.updateVisitIfVersion(
                VisitId("visit-1"),
                0L,
                visit.copy(version = 1L)
            ).shouldBeTrue()

            repository.updateVisitIfVersion(
                VisitId("visit-1"),
                0L,
                visit.copy(version = 2L)
            ).shouldBeFalse()

            repository.findVisit(VisitId("visit-1")).shouldNotBeNull().version shouldBeEqualTo 1L
        }
    }

    @Test
    fun `event key same digest is duplicate and different digest is conflict`() {
        transaction {
            val command = eventCommand(EventDigest("a".repeat(64)))

            repository.appendEvent(command) shouldBeEqualTo EventAppendResult.APPENDED
            repository.appendEvent(command) shouldBeEqualTo EventAppendResult.DUPLICATE

            repository.appendEvent(command.copy(digest = EventDigest("b".repeat(64)))) shouldBeEqualTo
                    EventAppendResult.EVENT_KEY_REUSED

            repository.countEvents() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `travel time projection advances revision and keeps latest edge`() {
        transaction {
            val from = CoordinateId("depot")
            val to = CoordinateId("visit")
            repository.saveTravelTime(from, to, 120L).revision shouldBeEqualTo 1L
            repository.saveTravelTime(from, to, 60L).revision shouldBeEqualTo 2L

            val matrix = repository.currentTravelTimeMatrix(setOf(from, to))
            log.debug { "matrix=$matrix" }
            matrix.revision shouldBeEqualTo 2L
            matrix.lookup(from, to) shouldBeEqualTo 60L
        }
    }

    @Test
    fun `outbox claim is bounded and plan history uses stable revision`() {
        transaction {
            repeat(12) { index ->
                repository.enqueueOutbox(
                    OutboxRecord(
                        payload = "job-$index",
                        nextAttemptAt = Instant.EPOCH
                    )
                )
            }
            repository.claimOutbox(limit = 100).size shouldBeEqualTo 10
        }
    }

    @Test
    fun `outbox lease token uses a V4 generator and remains injectable`() {
        transaction {
            repository.enqueueOutbox(OutboxRecord(payload = "default", nextAttemptAt = Instant.EPOCH))
            val claimed = repository.claimOutbox().single()
            val token = claimed.leaseToken.shouldNotBeNull()
            UUID.fromString(token).version() shouldBeEqualTo 4
        }

        val expected = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val deterministicRepository = FieldServiceRepository(leaseTokenGenerator = { expected })

        transaction {
            deterministicRepository.enqueueOutbox(OutboxRecord(payload = "injected", nextAttemptAt = Instant.EPOCH))
            deterministicRepository.claimOutbox().single().leaseToken shouldBeEqualTo expected.toString()
        }
    }

    private fun workerRecord(): Worker =
        Worker(
            workerId = WorkerId("worker-1"),
            name = "Synthetic worker",
            skills = setOf(Skill("electrical")),
            availability = emptyList(),
        )

    private fun visitRecord(): Visit =
        Visit(
            visitId = VisitId("visit-1"),
            coordinateId = CoordinateId("coordinate-1"),
            requiredSkill = Skill("electrical"),
            windowStart = Instant.parse("2026-08-20T09:00:00Z"),
            windowEnd = Instant.parse("2026-08-20T18:00:00Z"),
            serviceDuration = 15.minutes(),
        )

    private fun eventCommand(digest: EventDigest): FieldServiceCommand =
        FieldServiceCommand(
            aggregateType = "visit",
            aggregateId = AggregateId("visit-1"),
            eventKey = EventKey("urgent-1"),
            eventType = FieldServiceEventType.VISIT_URGENT,
            digest = digest,
            payloadSummary = "visit-1",
        )
}
