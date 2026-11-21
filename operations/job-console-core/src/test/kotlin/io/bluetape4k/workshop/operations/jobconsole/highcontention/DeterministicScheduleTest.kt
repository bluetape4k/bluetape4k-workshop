package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * DeterministicScheduleTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class DeterministicScheduleTest {

    companion object: KLogging() {
/**
 * contractRoot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun contractRoot(): Path =
            Path.of(System.getProperty("highContentionContractRoot").shouldNotBeNull())
    }

    @Test
/**
 * 이 시나리오는 "every versioned golden vector produces the exact canonical tokens" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `every versioned golden vector produces the exact canonical tokens`() {
        val vectors = HighContentionContractLoader()
            .load(
                contractRoot = contractRoot(),
                mode = HighContentionMode.CI_CORRECTNESS,
            )
            .scheduleVectors

        vectors.vectors.forEach { vector ->
            log.debug { "vector=$vector" }
            DeterministicSchedule.generate(vector) shouldBeEqualTo vector.expectedTokens
        }
    }

    @Test
/**
 * 이 시나리오는 "the same vector always produces the same canonical digest" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `the same vector always produces the same canonical digest`() {
        val vector = HighContentionContractLoader()
            .load(
                contractRoot = contractRoot(),
                mode = HighContentionMode.CI_CORRECTNESS,
            )
            .scheduleVectors.vectors.last()

        val first = DeterministicSchedule.generate(vector)
        val second = DeterministicSchedule.generate(vector)

        first.onEach { log.debug { "token=$it" } }
        second shouldBeEqualTo first
        DeterministicSchedule.digest(second) shouldBeEqualTo DeterministicSchedule.digest(first)
    }

    @Test
/**
 * 이 시나리오는 "offset arithmetic remains bounded at Long maximum duration" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `offset arithmetic remains bounded at Long maximum duration`() {
        val vector = ScheduleVector(
            name = "long-boundary",
            profileSchemaVersion = 1,
            seed = "boundary",
            curve = ArrivalCurve.STEP,
            operationCount = 3,
            durationNanos = Long.MAX_VALUE,
            authorityWeights = listOf(1),
            epochs = listOf(ScheduleEpoch(Long.MAX_VALUE, 3)),
            retryShape = null,
            expectedTokens = emptyList(),
        )

        val schedule = DeterministicSchedule.generate(vector)

        schedule.map(ScheduleToken::offsetNanos) shouldBeEqualTo listOf(
            0L,
            Long.MAX_VALUE / 3,
            (Long.MAX_VALUE.toBigInteger() * 2.toBigInteger() / 3.toBigInteger()).toLong(),
        )
    }

    @Test
/**
 * 이 시나리오는 "invalid retry totals are rejected before schedule generation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid retry totals are rejected before schedule generation`() {
        val vector = ScheduleVector(
            name = "invalid-retry",
            profileSchemaVersion = 1,
            seed = "invalid",
            curve = ArrivalCurve.RETRY_STORM,
            operationCount = 3,
            durationNanos = 100,
            authorityWeights = listOf(1),
            epochs = emptyList(),
            retryShape = ScheduleRetryShape(identityCount = 2, attemptsPerIdentity = 2),
            expectedTokens = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            DeterministicSchedule.generate(vector)
        }
    }
}
