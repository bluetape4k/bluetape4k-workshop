package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

internal class EventStoreRepositoryArchitectureTest {

    companion object: KLogging()

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
        EventLog.columns shouldContain EventLog.streamVersion
        EventLog.columns shouldContain EventLog.globalPosition
        EventLog.columns shouldContain EventLog.canonicalChecksum
        StreamHeads.columns shouldContain StreamHeads.version
        AppendFences.columns shouldContain AppendFences.nextGlobalPosition
        IdempotencyReceipts.columns shouldContain IdempotencyReceipts.fingerprint
        EventSnapshots.columns shouldContain EventSnapshots.streamVersion
        ProjectionCheckpoints.columns shouldContain ProjectionCheckpoints.fencingToken
    }
}
