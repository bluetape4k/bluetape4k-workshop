package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ProjectionLeaseRepositoryIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val leases = ProjectionLeaseRepository()
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-lease")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() = transaction(database) { SchemaUtils.create(ProjectionLeases) }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(ProjectionLeases) }

    @Test
    fun `expired takeover increments fencing token and stale release cannot clear the new owner`() {
        val first =
            transaction(database) { leases.acquire(PROJECTION, GENERATION, "owner-a", NOW) }.shouldNotBeNull()
        val second =
            transaction(database) {
                leases.acquire(PROJECTION, GENERATION, "owner-b", NOW.plusSeconds(LEASE_TAKEOVER_SECONDS))
            }.shouldNotBeNull()

        // 검증
        (second.fencingToken > first.fencingToken).shouldBeTrue()
        transaction(database) {
            leases.release(PROJECTION, GENERATION, first, NOW.plusSeconds(RENEW_SECONDS)).shouldBeFalse()
        }
        transaction(database) {
            leases.renew(PROJECTION, GENERATION, second, NOW.plusSeconds(RENEW_SECONDS)).shouldBeTrue()
        }
    }

    @Test
    fun `renewal becomes due five seconds after a fifteen second lease was acquired`() {
        val lease =
            transaction(database) { leases.acquire(PROJECTION, GENERATION, "owner-a", NOW) }.shouldNotBeNull()

        lease.isRenewalDue(NOW.plusSeconds(PROJECTION_LEASE_RENEW_SECONDS - 1)).shouldBeFalse()
        lease.isRenewalDue(NOW.plusSeconds(PROJECTION_LEASE_RENEW_SECONDS)).shouldBeTrue()
    }

    companion object {
        private const val PROJECTION = "voucher"
        private const val GENERATION = 1L
        private const val LEASE_TAKEOVER_SECONDS = 16L
        private const val RENEW_SECONDS = 17L
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
    }
}
