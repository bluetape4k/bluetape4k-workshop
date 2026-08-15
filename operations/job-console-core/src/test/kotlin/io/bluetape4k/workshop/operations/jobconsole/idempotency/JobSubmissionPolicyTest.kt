package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class JobSubmissionPolicyTest {

    @Test
    fun `default policy has bounded waiter and connection budgets`() {
        val policy = JobSubmissionIdempotencyPolicy()

        policy.maxWaitersPerKey shouldBeEqualTo 2
        policy.maxWaitersPerInstance shouldBeEqualTo 32
        policy.datasourcePoolSize shouldBeEqualTo 8
        policy.idempotencyDbConcurrency shouldBeEqualTo 4
        policy.pollInitialInterval shouldBeEqualTo Duration.ofMillis(25)
        policy.pollMaxInterval shouldBeEqualTo Duration.ofMillis(100)
        policy.fingerprint shouldBeEqualTo JobSubmissionIdempotencyPolicy().fingerprint
    }

    @Test
    fun `policy fingerprint changes when an operational bound changes`() {
        val baseline = JobSubmissionIdempotencyPolicy()
        val changed = baseline.copy(waiterTimeout = Duration.ofSeconds(3))

        assertNotEquals(baseline.fingerprint, changed.fingerprint)
    }

    @Test
    fun `policy rejects non-positive and inconsistent bounds at construction`() {
        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(maxWaitersPerKey = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(pollInitialInterval = Duration.ofMillis(101))
        }
        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(
                ownerLease = Duration.ofSeconds(1),
                prepareDeadline = Duration.ofSeconds(2),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(
                maxWaitersPerKey = 33,
                maxWaitersPerInstance = 32,
            )
        }
    }
}
