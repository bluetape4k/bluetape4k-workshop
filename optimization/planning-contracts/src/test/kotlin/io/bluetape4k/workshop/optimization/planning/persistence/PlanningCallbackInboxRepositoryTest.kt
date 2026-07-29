package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanningCallbackInboxRepositoryTest {

    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = PlanningCallbackInboxRepository()

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
            SchemaUtils.drop(PlanningCallbackInboxTable)
            SchemaUtils.create(PlanningCallbackInboxTable)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction {
            SchemaUtils.drop(PlanningCallbackInboxTable)
        }
    }

    @Test
    fun `duplicate provider event is inserted only once`() {
        val event = callbackEvent()

        transaction {
            repository.insertIfAbsent(event) shouldBeEqualTo true
            repository.insertIfAbsent(event) shouldBeEqualTo false
            repository.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `concurrent duplicate callbacks converge to one inbox row`() {
        val workers = 4
        val barrier = CyclicBarrier(workers)
        val inserted = AtomicInteger()

        MultithreadingTester()
            .workers(workers)
            .rounds(1)
            .add {
                barrier.await(5, TimeUnit.SECONDS)
                transaction {
                    if (repository.insertIfAbsent(callbackEvent())) {
                        inserted.incrementAndGet()
                    }
                }
            }
            .run()

        inserted.get() shouldBeEqualTo 1
        transaction { repository.count() } shouldBeEqualTo 1L
    }

    private fun callbackEvent() = PlanningCallbackInboxRecord(
        provider = PlanningProvider.FAKE,
        eventId = "fake-event-42",
        planningRequestId = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b"),
        providerRevision = 4,
        outcome = CallbackOutcome.RECEIVED,
    )
}
