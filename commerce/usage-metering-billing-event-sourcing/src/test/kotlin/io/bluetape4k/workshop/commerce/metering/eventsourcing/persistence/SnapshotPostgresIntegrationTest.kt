package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
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

        val actualReducerOne = reducerOne.shouldNotBeNull()
        val actualReducerTwo = reducerTwo.shouldNotBeNull()
        actualReducerOne.streamVersion.shouldBeEqualTo(20L)
        actualReducerOne.lastEventHash.shouldBeEqualTo("hash-20")
        actualReducerTwo.streamVersion.shouldBeEqualTo(30L)
        fixture.executor.transaction { repository.latest(stream.copy(tenantId = "tenant-b"), 1) }.shouldBeNull()
    }

    @Test
    fun `snapshot history is append-only`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Meter", "api_calls")
        val stored = fixture.executor.transaction { repository.append(snapshot(stream, 10, 1, "hash-10")) }

        assertFailsWith<UnsupportedOperationException> {
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
