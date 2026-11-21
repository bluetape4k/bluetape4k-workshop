package io.bluetape4k.workshop.operations.jobconsole.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * job lifecycle의 전이표를 업무 시나리오로 검증한다.
 * queued/running 취소, retry budget, terminal 불변식, optimistic revision
 * 충돌을 각각 분리해 stale worker가 상태를 덮어쓰지 못함을 증명한다.
 */
class JobTransitionsTest {

    companion object: KLogging()

    @Test
    /** FIFO queue에서 claim된 job이 running으로 시작하는 기본 전이를 검증한다. */
    /** FIFO queue에서 claim된 job이 running으로 시작하는 기본 전이를 검증한다. */
    fun `claim starts the oldest queued job`() {
        JobTransitions.next(JobState.QUEUED, JobSignal.CLAIM) shouldBeEqualTo JobState.RUNNING
    }

    @Test
    /** queued 즉시 취소와 running의 지연 취소가 서로 다른 durable 경로임을 검증한다. */
    /** queued 즉시 취소와 running의 지연 취소가 서로 다른 durable 경로임을 검증한다. */
    fun `queued and running cancellation follow different durable paths`() {
        JobTransitions.next(JobState.QUEUED, JobSignal.CANCEL) shouldBeEqualTo JobState.CANCELLED
        JobTransitions.next(JobState.RUNNING, JobSignal.CANCEL) shouldBeEqualTo JobState.CANCEL_REQUESTED
        JobTransitions.next(JobState.CANCEL_REQUESTED, JobSignal.CHECKPOINT) shouldBeEqualTo JobState.CANCELLED
    }

    @Test
    /** retry 가능한 실패는 같은 queue identity를 유지하고 budget 소진만 dead-letter가 된다. */
    /** retry 가능한 실패는 같은 queue identity를 유지하고 budget 소진만 dead-letter가 된다. */
    fun `retry preserves queue identity until the retry budget is exhausted`() {
        JobTransitions.next(JobState.RUNNING, JobSignal.RETRYABLE_FAILURE) shouldBeEqualTo JobState.QUEUED
        JobTransitions.next(JobState.RUNNING, JobSignal.RETRY_EXHAUSTED) shouldBeEqualTo JobState.DEAD_LETTERED
    }

    @Test
    /** 성공과 재시도 불가능한 실패가 이후 command를 받지 않는 terminal 상태임을 확인한다. */
    /** 성공과 재시도 불가능한 실패가 이후 command를 받지 않는 terminal 상태임을 확인한다. */
    fun `success and non retryable failure are terminal`() {
        JobTransitions.next(JobState.RUNNING, JobSignal.SUCCESS) shouldBeEqualTo JobState.SUCCEEDED
        JobTransitions.next(JobState.RUNNING, JobSignal.NON_RETRYABLE_FAILURE) shouldBeEqualTo JobState.FAILED
    }

    @Test
    /** 모든 terminal 상태가 늦게 도착한 cancel signal을 거부하는지 확인한다. */
    /** 모든 terminal 상태가 늦게 도착한 cancel signal을 거부하는지 확인한다. */
    fun `terminal states reject further commands`() {
        JobState.entries.filter(JobState::terminal).forEach { terminal ->
            assertFailsWith<InvalidJobTransition> {
                JobTransitions.next(terminal, JobSignal.CANCEL)
            }.code shouldBeEqualTo JobProblemCode.INVALID_TRANSITION
        }
    }

    @Test
    /** 현재 revision보다 오래된 worker는 signal 종류와 무관하게 먼저 거부된다. */
    /** 현재 revision보다 오래된 worker는 signal 종류와 무관하게 먼저 거부된다. */
    fun `stale revision is rejected before transition evaluation`() {
        assertFailsWith<InvalidJobTransition> {
            JobTransitions.next(
                state = JobState.RUNNING,
                signal = JobSignal.SUCCESS,
                currentRevision = 4,
                expectedRevision = 3,
            )
        }.code shouldBeEqualTo JobProblemCode.STALE_REVISION
    }
}
