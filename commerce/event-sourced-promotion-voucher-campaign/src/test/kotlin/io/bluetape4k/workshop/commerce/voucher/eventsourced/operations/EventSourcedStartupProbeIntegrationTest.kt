package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedStartupProbeIntegrationTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database
    private lateinit var probe: EventSourcedStartupProbe

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-startup-probe", maximumPoolSize = 1)
        database = postgresDatabase.database
        probe = EventSourcedOperationsConfiguration().eventSourcedStartupProbe(
            EventSourcedExposedDatabaseRegistration(database),
            EventSourcedDatabasePermitGate(),
        )
    }

    @BeforeEach
    fun clearSchema() {
        transaction(database) { SchemaUtils.drop(EventLog) }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(EventLog) }
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @Test
    fun `probe rejects a missing event authority schema and accepts it after creation`() {
        assertFailsWith<IllegalStateException> { probe.verify() }

        transaction(database) { SchemaUtils.create(EventLog) }

        probe.verify()
    }

    @Test
    fun `probe reserves the readiness permit before requesting a Hikari connection`() {
        val permits = EventSourcedDatabasePermitGate(
            acquireTimeout = 50.millis(),
        )
        val contestedProbe = EventSourcedOperationsConfiguration()
            .eventSourcedStartupProbe(
                EventSourcedExposedDatabaseRegistration(database),
                permits,
            )
        val permitHeld = CountDownLatch(1)
        val releasePermit = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val owner = executor.submit {
                permits.withPermit(EventSourcedDatabaseLane.READINESS) {
                    permitHeld.countDown()
                    releasePermit.await(5.seconds).shouldBeTrue()
                }
            }
            permitHeld.await(5.seconds).shouldBeTrue()

            assertFailsWith<DatabaseBulkheadRejected> {
                contestedProbe.verify()
            }

            releasePermit.countDown()
            owner.get(5.seconds)
        }
    }
}
