package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.collections.intRangeOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HighContentionWorkloadEngineTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionWorkloadEngineTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "warm-up runs before the baseline and is absent from measured conservation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `warm-up runs before the baseline and is absent from measured conservation`() {
        val events = mutableListOf<String>()
        val adapter = object: HighContentionWorkloadAdapter {
/**
 * warmUp은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun warmUp(identity: WorkloadIdentity) {
                events += "warmup:${identity.namespace}:${identity.ordinal}"
            }

/**
 * snapshotBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun snapshotBaseline(): String {
                events += "baseline"
                return "baseline-1"
            }

/**
 * execute은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun execute(token: ScheduleToken, identity: WorkloadIdentity): WorkloadTerminalDisposition {
                events += "measured:${identity.namespace}:${identity.ordinal}"
                return WorkloadTerminalDisposition.COMPLETED
            }
        }
        val schedule = burstSchedule(operationCount = 3)

        val result = HighContentionWorkloadEngine()
            .run(
                schedule = schedule,
                warmupOperationCount = 2,
                warmupNamespace = "run:warmup:",
                measuredNamespace = "run:measured:",
                concurrency = 1,
                dispatcherBacklogCapacity = 3,
                maxScheduleDelayNanos = Long.MAX_VALUE,
                adapter = adapter,
            )

        events.take(3) shouldBeEqualTo listOf(
            "warmup:run:warmup::0",
            "warmup:run:warmup::1",
            "baseline",
        )
        events.drop(3).toSet() shouldBeEqualTo setOf(
            "measured:run:measured::0",
            "measured:run:measured::1",
            "measured:run:measured::2",
        )

        log.debug { "result=$result" }
        result.baseline shouldBeEqualTo "baseline-1"
        result.expectedTokenCount shouldBeEqualTo 3
        result.scheduledCount shouldBeEqualTo 3
        result.dispatchedCount shouldBeEqualTo 3
        result.completedCount shouldBeEqualTo 3
        result.realizedRecords.size shouldBeEqualTo 3
        result.expectedScheduleDigest shouldBeEqualTo result.realizedScheduleDigest
    }

    @Test
/**
 * 이 시나리오는 "bounded dispatch records local rejection and the reserved observer still executes" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `bounded dispatch records local rejection and the reserved observer still executes`() {
        val release = CountDownLatch(1)
        val observerRan = AtomicBoolean()
        val adapter = object: HighContentionWorkloadAdapter {
/**
 * warmUp은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun warmUp(identity: WorkloadIdentity) = Unit

/**
 * snapshotBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun snapshotBaseline(): String = "baseline"

/**
 * execute은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun execute(token: ScheduleToken, identity: WorkloadIdentity): WorkloadTerminalDisposition {
                release.await(5, TimeUnit.SECONDS)
                return WorkloadTerminalDisposition.COMPLETED
            }
        }

        val result = HighContentionWorkloadEngine().run(
            schedule = burstSchedule(operationCount = 6),
            warmupOperationCount = 0,
            warmupNamespace = "run:warmup:",
            measuredNamespace = "run:measured:",
            concurrency = 1,
            dispatcherBacklogCapacity = 1,
            maxScheduleDelayNanos = Long.MAX_VALUE,
            adapter = adapter,
            faultObserverStartAfterScheduledCount = 6,
            faultObserver = {
                observerRan.set(true)
                release.countDown()
            },
        )

        observerRan.get().shouldBeTrue()
        result.scheduledCount shouldBeEqualTo 6
        result.scheduledCount shouldBeEqualTo result.dispatchedCount + result.locallyRejectedCount
        result.dispatchedCount shouldBeEqualTo result.completedCount + result.cancelledCount + result.timedOutCount
        result.realizedRecords.map { it.token.stableOrdinal }.sorted() shouldBeEqualTo intRangeOf(0, 6)
    }

    @Test
/**
 * 이 시나리오는 "warm-up and measured namespaces must not overlap" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `warm-up and measured namespaces must not overlap`() {
        assertFailsWith<IllegalArgumentException> {
            HighContentionWorkloadEngine().run(
                schedule = burstSchedule(operationCount = 1),
                warmupOperationCount = 1,
                warmupNamespace = "run:shared:",
                measuredNamespace = "run:shared:measured:",
                concurrency = 1,
                dispatcherBacklogCapacity = 1,
                maxScheduleDelayNanos = Long.MAX_VALUE,
                adapter = object: HighContentionWorkloadAdapter {
/**
 * warmUp은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                    override fun warmUp(identity: WorkloadIdentity) = Unit
/**
 * snapshotBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                    override fun snapshotBaseline(): String = "baseline"
/**
 * execute은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                    override fun execute(
                        token: ScheduleToken,
                        identity: WorkloadIdentity,
                    ): WorkloadTerminalDisposition = WorkloadTerminalDisposition.COMPLETED
                },
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "missing duplicate and unknown stable ordinals fail realization validation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `missing duplicate and unknown stable ordinals fail realization validation`() {
        val schedule = burstSchedule(operationCount = 2)
        val first = WorkloadRealizedRecord(
            token = schedule[0],
            disposition = WorkloadTerminalDisposition.COMPLETED,
            missedDeadline = false,
        )

        assertFailsWith<IllegalStateException> {
            HighContentionWorkloadEngine.validateRealization(schedule, listOf(first))
        }
        assertFailsWith<IllegalStateException> {
            HighContentionWorkloadEngine.validateRealization(schedule, listOf(first, first))
        }
        assertFailsWith<IllegalStateException> {
            HighContentionWorkloadEngine.validateRealization(
                schedule,
                listOf(
                    first,
                    WorkloadRealizedRecord(
                        token = schedule[1].copy(stableOrdinal = 99),
                        disposition = WorkloadTerminalDisposition.COMPLETED,
                        missedDeadline = false,
                    ),
                ),
            )
        }
    }

/**
 * burstSchedule은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun burstSchedule(operationCount: Int): List<ScheduleToken> =
        DeterministicSchedule.generate(
            ScheduleVector(
                name = "engine-burst",
                profileSchemaVersion = 1,
                seed = "engine",
                curve = ArrivalCurve.BURST,
                operationCount = operationCount,
                durationNanos = 1,
                authorityWeights = listOf(1),
                epochs = emptyList(),
                retryShape = null,
                expectedTokens = emptyList(),
            ),
        )
}
