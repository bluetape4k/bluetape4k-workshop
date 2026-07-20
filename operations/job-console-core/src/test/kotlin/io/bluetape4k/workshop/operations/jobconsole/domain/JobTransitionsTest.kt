package io.bluetape4k.workshop.operations.jobconsole.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class JobTransitionsTest {

    @Test
    fun `claim starts the oldest queued job`() {
        JobTransitions.next(JobState.QUEUED, JobSignal.CLAIM) shouldBeEqualTo JobState.RUNNING
    }

    @Test
    fun `queued and running cancellation follow different durable paths`() {
        JobTransitions.next(JobState.QUEUED, JobSignal.CANCEL) shouldBeEqualTo JobState.CANCELLED
        JobTransitions.next(JobState.RUNNING, JobSignal.CANCEL) shouldBeEqualTo JobState.CANCEL_REQUESTED
        JobTransitions.next(JobState.CANCEL_REQUESTED, JobSignal.CHECKPOINT) shouldBeEqualTo JobState.CANCELLED
    }

    @Test
    fun `retry preserves queue identity until the retry budget is exhausted`() {
        JobTransitions.next(JobState.RUNNING, JobSignal.RETRYABLE_FAILURE) shouldBeEqualTo JobState.QUEUED
        JobTransitions.next(JobState.RUNNING, JobSignal.RETRY_EXHAUSTED) shouldBeEqualTo JobState.DEAD_LETTERED
    }

    @Test
    fun `success and non retryable failure are terminal`() {
        JobTransitions.next(JobState.RUNNING, JobSignal.SUCCESS) shouldBeEqualTo JobState.SUCCEEDED
        JobTransitions.next(JobState.RUNNING, JobSignal.NON_RETRYABLE_FAILURE) shouldBeEqualTo JobState.FAILED
    }

    @Test
    fun `terminal states reject further commands`() {
        JobState.entries.filter(JobState::terminal).forEach { terminal ->
            assertFailsWith<InvalidJobTransition> {
                JobTransitions.next(terminal, JobSignal.CANCEL)
            }.code shouldBeEqualTo JobProblemCode.INVALID_TRANSITION
        }
    }

    @Test
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
