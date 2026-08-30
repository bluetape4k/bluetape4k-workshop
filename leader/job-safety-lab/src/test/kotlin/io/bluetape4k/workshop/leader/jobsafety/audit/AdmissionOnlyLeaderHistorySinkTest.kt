package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.junit.jupiter.api.Test
import java.time.Instant

internal class AdmissionOnlyLeaderHistorySinkTest {

    @Test
    fun `admission sink returns lifecycle key without persistence`() {
        val sink = AdmissionOnlyLeaderHistorySink()
        val record = record()

        val key = sink.recordAcquired(record).shouldNotBeNull()

        key.lockName shouldBeEqualTo record.lockName
        key.token shouldBeEqualTo record.token
        key.slotId shouldBeEqualTo record.slotId
        key.historyId.shouldNotBeNull()
        sink.recordCompleted(key, FINISHED_AT, durationMs = 42)
        sink.recordFailed(
            key = key,
            finishedAt = FINISHED_AT,
            durationMs = 43,
            errorType = IllegalStateException::class.qualifiedName,
            errorMessage = "failure",
        )
        sink.deleteOlderThan(Instant.now(), limit = 10) shouldBeEqualTo 0
    }

    @Test
    fun `each admission creates an independent lifecycle key`() {
        val sink = AdmissionOnlyLeaderHistorySink()

        val first = sink.recordAcquired(record()).shouldNotBeNull()
        val second = sink.recordAcquired(record()).shouldNotBeNull()

        first.historyId.shouldNotBeNull()
        second.historyId.shouldNotBeNull()
        (first.historyId == second.historyId) shouldBeEqualTo false
    }

    private fun record(): LeaderLockHistoryRecord = LeaderLockHistoryRecord(
        lockName = "job-safety:sample",
        token = "token-secret",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = ACQUIRED_AT,
        lockedUntil = FINISHED_AT,
        status = LeaderHistoryStatus.ACQUIRED,
        slotId = "slot-a",
    )

    private companion object {
        val ACQUIRED_AT: Instant = Instant.parse("2026-08-30T00:00:00Z")
        val FINISHED_AT: Instant = Instant.parse("2026-08-30T00:00:01Z")
    }
}
