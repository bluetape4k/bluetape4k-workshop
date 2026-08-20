package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceTables
import io.bluetape4k.workshop.optimization.fieldservice.persistence.OutboxRecord
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldServiceOutboxWorkerTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = FieldServiceRepository()

    @BeforeAll
    fun connect() {
        Database.connect(postgres.jdbcUrl, "org.postgresql.Driver", requireNotNull(postgres.username), requireNotNull(postgres.password))
    }

    @BeforeEach
    fun schema() {
        transaction {
            SchemaUtils.drop(*FieldServiceTables.all.reversedArray())
            SchemaUtils.create(*FieldServiceTables.all)
        }
    }

    @Test
    fun `outbox worker claims no more than ten rows and converges replay`() {
        transaction {
            repeat(12) { index -> repository.enqueueOutbox(OutboxRecord(payload = "job-$index", nextAttemptAt = Instant.EPOCH)) }
        }
        val worker = FieldServiceOutboxWorker(repository, handler = { ReplayOutcome.COMPLETED })

        val result = worker.processOutboxBatch(maxItems = 100)

        result.claimed shouldBeEqualTo 10
        result.completed shouldBeEqualTo 10
    }

    @Test
    fun `dead letter outcome is terminal before max attempts`() {
        transaction {
            repository.enqueueOutbox(OutboxRecord(payload = "poison", nextAttemptAt = Instant.EPOCH))
        }
        val worker = FieldServiceOutboxWorker(repository, handler = { ReplayOutcome.DEAD_LETTER })

        val result = worker.processOutboxBatch()

        result.deadLetter shouldBeEqualTo 1
        transaction { repository.claimOutbox().size shouldBeEqualTo 0 }
    }
}
