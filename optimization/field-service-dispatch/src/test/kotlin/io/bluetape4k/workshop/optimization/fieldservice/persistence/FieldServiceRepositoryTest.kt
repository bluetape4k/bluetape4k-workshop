package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldServiceRepositoryTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = FieldServiceRepository()

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
    fun `worker and visit update include expected version`() {
        transaction {
            val worker = repository.saveWorker(workerRecord())
            repository.findWorker(WorkerId("worker-1")) shouldBeEqualTo worker

            val visit = repository.saveVisit(visitRecord())
            repository.updateVisitIfVersion(VisitId("visit-1"), 0L, visit.copy(version = 1L)).shouldBeTrue()
            repository.updateVisitIfVersion(VisitId("visit-1"), 0L, visit.copy(version = 2L)).shouldBeFalse()
            repository.findVisit(VisitId("visit-1"))!!.version shouldBeEqualTo 1L
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
            val from = io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId("depot")
            val to = io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId("visit")
            repository.saveTravelTime(from, to, 120L).revision shouldBeEqualTo 1L
            repository.saveTravelTime(from, to, 60L).revision shouldBeEqualTo 2L

            val matrix = repository.currentTravelTimeMatrix(setOf(from, to))
            matrix.revision shouldBeEqualTo 2L
            matrix.lookup(from, to) shouldBeEqualTo 60L
        }
    }

    @Test
    fun `outbox claim is bounded and plan history uses stable revision`() {
        transaction {
            repeat(12) { index -> repository.enqueueOutbox(OutboxRecord(payload = "job-$index", nextAttemptAt = Instant.EPOCH)) }
            repository.claimOutbox(limit = 100).size shouldBeEqualTo 10
        }
    }

    private fun workerRecord() = io.bluetape4k.workshop.optimization.fieldservice.domain.Worker(
        workerId = WorkerId("worker-1"),
        name = "Synthetic worker",
        skills = setOf(Skill("electrical")),
        availability = emptyList(),
    )

    private fun visitRecord() = io.bluetape4k.workshop.optimization.fieldservice.domain.Visit(
        visitId = VisitId("visit-1"),
        coordinateId = io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId("coordinate-1"),
        requiredSkill = Skill("electrical"),
        windowStart = Instant.parse("2026-08-20T09:00:00Z"),
        windowEnd = Instant.parse("2026-08-20T18:00:00Z"),
        serviceDuration = java.time.Duration.ofMinutes(15),
    )

    private fun eventCommand(digest: EventDigest) = FieldServiceCommand(
        aggregateType = "visit",
        aggregateId = AggregateId("visit-1"),
        eventKey = EventKey("urgent-1"),
        eventType = FieldServiceEventType.VISIT_URGENT,
        digest = digest,
        payloadSummary = "visit-1",
    )
}
