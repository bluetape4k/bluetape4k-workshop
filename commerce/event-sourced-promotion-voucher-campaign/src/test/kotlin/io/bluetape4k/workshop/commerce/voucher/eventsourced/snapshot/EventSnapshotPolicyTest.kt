package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.junit.jupiter.api.Test
import java.util.UUID

internal class EventSnapshotPolicyTest {

    @Test
    fun `snapshot threshold writes only every two hundred fifty events`() {
        shouldWriteSnapshot(SNAPSHOT_THRESHOLD - 1).shouldBeFalse()
        shouldWriteSnapshot(SNAPSHOT_THRESHOLD).shouldBeTrue()
    }

    @Test
    fun `snapshot rejects state larger than one mebibyte`() {
        assertFailsWith<IllegalArgumentException> {
            EventSnapshot(
                stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                metadata = SnapshotMetadata(250, schemaVersion = 1, keyVersion = 1),
                canonicalState = "x".repeat(MAX_SNAPSHOT_BYTES + 1),
            )
        }
    }
}
