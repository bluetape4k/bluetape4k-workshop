package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

internal class EventStoreRepositoryArchitectureTest {

    @Test
    fun `event repository is a connection free bluetape repository`() {
        ExposedJdbcRepository::class.java.isAssignableFrom(EventLogRepository::class.java).shouldBeTrue()
    }

    @Test
    fun `event repository rejects generic mutation`() {
        assertFailsWith<UnsupportedOperationException> {
            EventLogRepository().deleteAll()
        }
    }

    @Test
    fun `event log and stream head expose append authority columns`() {
        EventLog.columns.contains(EventLog.streamVersion).shouldBeTrue()
        EventLog.columns.contains(EventLog.globalPosition).shouldBeTrue()
        EventLog.columns.contains(EventLog.canonicalChecksum).shouldBeTrue()
        StreamHeads.columns.contains(StreamHeads.version).shouldBeTrue()
        AppendFences.columns.contains(AppendFences.nextGlobalPosition).shouldBeTrue()
        IdempotencyReceipts.columns.contains(IdempotencyReceipts.fingerprint).shouldBeTrue()
        EventSnapshots.columns.contains(EventSnapshots.streamVersion).shouldBeTrue()
        ProjectionCheckpoints.columns.contains(ProjectionCheckpoints.fencingToken).shouldBeTrue()
    }
}
