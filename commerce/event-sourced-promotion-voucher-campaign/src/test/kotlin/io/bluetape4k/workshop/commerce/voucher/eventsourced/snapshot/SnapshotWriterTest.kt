package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.junit.jupiter.api.Test
import java.sql.SQLTransientConnectionException
import java.util.UUID

internal class SnapshotWriterTest {

    @Test
    fun `snapshot write failure is isolated from its already committed append`() {
        val writer = SnapshotWriter(FailingSnapshotTransactionRunner(), EventSnapshotRepository())
        val snapshot =
            EventSnapshot(
                stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                metadata = SnapshotMetadata(250, schemaVersion = 1, keyVersion = 1),
                canonicalState = "state-250",
            )

        writer.writeAfterAppend(snapshot).shouldBeFalse()
    }

    private class FailingSnapshotTransactionRunner : SnapshotTransactionRunner {
        override fun <T> inTransaction(block: () -> T): T =
            throw SQLTransientConnectionException("snapshot store unavailable")
    }
}
