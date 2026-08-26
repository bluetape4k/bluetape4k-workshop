package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import java.time.Instant
import org.junit.jupiter.api.Test

class ShiftCoverageInboxRetryConvergenceTest {
    @Test
    fun `revision and digest converge without automatic replay after exhaustion`() {
        val inbox = ShiftCoverageInboxService()
        val event = ShiftCoverageInboxEvent(ShiftCoverageProvider.FAKE, EventId("event-1"), "a".repeat(64), 1L)
        inbox.claim(event).status shouldBeEqualTo ShiftCoverageInboxStatus.RECEIVED
        repeat(5) { inbox.fail(event, Instant.parse("2026-08-24T09:00:00Z")) }
        inbox.find(event.provider, event.eventId)?.status shouldBeEqualTo ShiftCoverageInboxStatus.RETRY_EXHAUSTED
        inbox.claim(event).status shouldBeEqualTo ShiftCoverageInboxStatus.RETRY_EXHAUSTED
        inbox.requeue(event.provider, event.eventId, "manager reason").status shouldBeEqualTo ShiftCoverageInboxStatus.RECEIVED
        inbox.claim(event.copy(revision = 1L)).status shouldBeEqualTo ShiftCoverageInboxStatus.DUPLICATE
        inbox.claim(event.copy(revision = 2L)).status shouldBeEqualTo ShiftCoverageInboxStatus.APPLIED
        inbox.claim(event.copy(revision = 1L, digest = "c".repeat(64))).status shouldBeEqualTo ShiftCoverageInboxStatus.EVENT_KEY_REUSED
    }
}
