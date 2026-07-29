package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotPostgresIntegrationTest {
    private val fixture = EventStoreDatabaseFixture()
    private val repository = SnapshotRepository()

    @Test
    fun `latest selects the highest snapshot for the requested reducer version`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Meter", "api_calls")
        fixture.executor.transaction {
            repository.append(snapshot(stream, 10, 1, "hash-10"))
            repository.append(snapshot(stream, 20, 1, "hash-20"))
            repository.append(snapshot(stream, 30, 2, "hash-30"))
        }

        val reducerOne = fixture.executor.transaction { repository.latest(stream, 1) }
        val reducerTwo = fixture.executor.transaction { repository.latest(stream, 2) }

        assertEquals(20L, reducerOne?.streamVersion)
        assertEquals("hash-20", reducerOne?.lastEventHash)
        assertEquals(30L, reducerTwo?.streamVersion)
        assertNull(fixture.executor.transaction { repository.latest(stream.copy(tenantId = "tenant-b"), 1) })
    }

    @Test
    fun `snapshot history is append-only`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Meter", "api_calls")
        val stored = fixture.executor.transaction { repository.append(snapshot(stream, 10, 1, "hash-10")) }

        assertThrows(UnsupportedOperationException::class.java) {
            fixture.executor.transaction { repository.save(AggregateSnapshotEntity[stored.snapshotId]) }
        }
    }

    private fun snapshot(
        stream: StreamKey,
        streamVersion: Long,
        reducerVersion: Int,
        lastEventHash: String,
    ): NewAggregateSnapshot = NewAggregateSnapshot(
        stream = stream,
        streamVersion = streamVersion,
        reducerVersion = reducerVersion,
        statePayload = """{"meterCode":"api_calls","version":$streamVersion}""",
        lastEventHash = lastEventHash,
        createdAt = Instant.parse("2026-07-01T00:00:00Z").plusSeconds(streamVersion),
    )
}
