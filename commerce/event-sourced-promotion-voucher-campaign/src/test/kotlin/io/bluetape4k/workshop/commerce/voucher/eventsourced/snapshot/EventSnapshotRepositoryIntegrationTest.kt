package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSnapshots
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
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
import java.util.UUID

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSnapshotRepositoryIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = EventSnapshotRepository()
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-event-snapshot")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() = transaction(database) { SchemaUtils.create(EventSnapshots) }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(EventSnapshots) }

    @Test
    fun `latest returns no snapshot until a stream checkpoint is persisted`() {
        val stream = stream()

        transaction(database) { repository.latest(stream) }.shouldBeNull()
        transaction(database) { repository.save(snapshot(stream, 250, "state-250")) }
        val latest = transaction(database) { repository.latest(stream) }

        latest.shouldNotBeNull().metadata.streamVersion shouldBeEqualTo 250L
        latest.canonicalState shouldBeEqualTo "state-250"
    }

    @Test
    fun `latest snapshot uses the greatest stream version`() {
        val stream = stream()
        transaction(database) {
            repository.save(snapshot(stream, 250, "state-250"))
            repository.save(snapshot(stream, 500, "state-500"))
        }

        transaction(database) { repository.latest(stream) }?.metadata?.streamVersion shouldBeEqualTo 500L
    }

    private fun stream() = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())

    private fun snapshot(stream: StreamKey, version: Long, state: String) =
        EventSnapshot(
            stream = stream,
            metadata = SnapshotMetadata(version, schemaVersion = 1, keyVersion = 1),
            canonicalState = state,
            createdAt = NOW,
        )

    companion object {
        private val NOW = Instant.parse("2026-07-23T12:00:00Z")
    }
}
