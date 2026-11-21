package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

/** 여러 application instance가 공유해야 하는 waiter·body·retention policy와 fingerprint를 검증한다. */
/** 여러 application instance가 공유해야 하는 waiter·body·retention policy와 fingerprint를 검증한다. */
class JobSubmissionPolicyTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "default policy has bounded waiter and connection budgets" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default policy has bounded waiter and connection budgets`() {
        val policy = JobSubmissionIdempotencyPolicy()

        log.debug { "policy=$policy" }
        policy.maxWaitersPerKey shouldBeEqualTo 2
        policy.maxWaitersPerInstance shouldBeEqualTo 32
        policy.datasourcePoolSize shouldBeEqualTo 8
        policy.idempotencyDbConcurrency shouldBeEqualTo 4
        policy.pollInitialInterval shouldBeEqualTo 25.millis()
        policy.pollMaxInterval shouldBeEqualTo 100.millis()
        policy.fingerprint shouldBeEqualTo JobSubmissionIdempotencyPolicy().fingerprint
    }

    @Test
/**
 * 이 시나리오는 "policy fingerprint changes when an operational bound changes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `policy fingerprint changes when an operational bound changes`() {
        val baseline = JobSubmissionIdempotencyPolicy()
        val changed = baseline.copy(waiterTimeout = 3.seconds())

        baseline.fingerprint shouldNotBeEqualTo changed.fingerprint
    }

    @Test
/**
 * 이 시나리오는 "policy rejects non-positive and inconsistent bounds at construction" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `policy rejects non-positive and inconsistent bounds at construction`() {
        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(maxWaitersPerKey = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(pollInitialInterval = 101.millis())
        }
        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(
                ownerLease = 1.seconds(),
                prepareDeadline = 2.seconds(),
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
