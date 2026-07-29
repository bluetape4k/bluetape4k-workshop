package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.modulith.events.EventPublication
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PublicationSnapshotRepositoryTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val table = ExposedEventPublicationTable("commerce_publication_snapshot_test")
    private val repository = PublicationSnapshotRepository(table)

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = requireNotNull(postgres.username),
            password = requireNotNull(postgres.password)
        )
    }

    @BeforeEach
    fun createSchema() =
        transaction {
            SchemaUtils.drop(table)
            SchemaUtils.create(table)
        }

    @AfterEach
    fun dropSchema() = transaction { SchemaUtils.drop(table) }

    @Test
    fun `publication snapshot uses one bounded aggregate query`() {
        val oldestIncomplete = Instant.parse("2026-07-18T00:00:00Z")
        val selectCount = AtomicInteger()

        val snapshot =
            transaction {
                insertPublication(EventPublication.Status.PUBLISHED, oldestIncomplete.plusSeconds(10))
                insertPublication(EventPublication.Status.PROCESSING, oldestIncomplete.plusSeconds(20))
                insertPublication(EventPublication.Status.FAILED, oldestIncomplete.plusSeconds(30))
                insertPublication(EventPublication.Status.RESUBMITTED, oldestIncomplete.plusSeconds(40))
                insertPublication(
                    EventPublication.Status.COMPLETED,
                    oldestIncomplete.minusSeconds(10),
                    completionDate = oldestIncomplete.minusSeconds(5)
                )
                insertPublication(status = null, publicationDate = oldestIncomplete)

                addLogger(selectCountingLogger(selectCount))
                repository.snapshot()
            }

        selectCount.get() shouldBeEqualTo 1
        snapshot.published shouldBeEqualTo 1
        snapshot.processing shouldBeEqualTo 1
        snapshot.failed shouldBeEqualTo 1
        snapshot.resubmitted shouldBeEqualTo 1
        snapshot.completed shouldBeEqualTo 1
        snapshot.oldestIncomplete shouldBeEqualTo oldestIncomplete
    }

    @Test
    fun `unknown publication status fails instead of hiding corrupt evidence`() {
        transaction {
            table.insert { row ->
                row[id] = Uuid.parse(UUID.randomUUID().toString())
                row[listenerId] = "test-listener"
                row[eventType] = "test.Event"
                row[serializedEvent] = "{}"
                row[publicationDate] = Instant.parse("2026-07-18T00:00:00Z")
                row[completionDate] = null
                row[status] = "UNKNOWN"
                row[completionAttempts] = 0
                row[lastResubmissionDate] = Instant.parse("2026-07-18T00:00:00Z")
            }

            assertFailsWith<IllegalArgumentException> { repository.snapshot() }
        }
    }

    private fun Transaction.insertPublication(
        status: EventPublication.Status?,
        publicationDate: Instant,
        completionDate: Instant? = null,
    ) {
        table.insert { row ->
            row[table.id] = Uuid.parse(UUID.randomUUID().toString())
            row[table.listenerId] = "test-listener"
            row[table.eventType] = "test.Event"
            row[table.serializedEvent] = "{}"
            row[table.publicationDate] = publicationDate
            row[table.completionDate] = completionDate
            row[table.status] = status?.name
            row[table.completionAttempts] = 0
            row[table.lastResubmissionDate] = publicationDate
        }
    }

    private fun selectCountingLogger(counter: AtomicInteger) =
        object : SqlLogger {
            override fun log(
                context: StatementContext,
                transaction: Transaction,
            ) {
                if (context.statement.type.name == "SELECT") counter.incrementAndGet()
            }
        }
}
