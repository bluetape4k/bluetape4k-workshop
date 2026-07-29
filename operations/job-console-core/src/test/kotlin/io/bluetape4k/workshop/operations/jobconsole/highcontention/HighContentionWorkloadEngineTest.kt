package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HighContentionWorkloadEngineTest {

    @Test
    fun `warm-up runs before the baseline and is absent from measured conservation`() {
        val events = mutableListOf<String>()
        val adapter = object : HighContentionWorkloadAdapter {
            override fun warmUp(identity: WorkloadIdentity) {
                events += "warmup:${identity.namespace}:${identity.ordinal}"
            }

            override fun snapshotBaseline(): String {
                events += "baseline"
                return "baseline-1"
            }

            override fun execute(token: ScheduleToken, identity: WorkloadIdentity): WorkloadTerminalDisposition {
                events += "measured:${identity.namespace}:${identity.ordinal}"
                return WorkloadTerminalDisposition.COMPLETED
            }
        }
        val schedule = burstSchedule(operationCount = 3)

        val result = HighContentionWorkloadEngine().run(
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
        result.baseline shouldBeEqualTo "baseline-1"
        result.expectedTokenCount shouldBeEqualTo 3
        result.scheduledCount shouldBeEqualTo 3
        result.dispatchedCount shouldBeEqualTo 3
        result.completedCount shouldBeEqualTo 3
        result.realizedRecords.size shouldBeEqualTo 3
        result.expectedScheduleDigest shouldBeEqualTo result.realizedScheduleDigest
    }

    @Test
    fun `bounded dispatch records local rejection and the reserved observer still executes`() {
        val release = CountDownLatch(1)
        val observerRan = AtomicBoolean()
        val adapter = object : HighContentionWorkloadAdapter {
            override fun warmUp(identity: WorkloadIdentity) = Unit

            override fun snapshotBaseline(): String = "baseline"

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

        observerRan.get() shouldBeEqualTo true
        result.scheduledCount shouldBeEqualTo 6
        result.scheduledCount shouldBeEqualTo result.dispatchedCount + result.locallyRejectedCount
        result.dispatchedCount shouldBeEqualTo result.completedCount +
            result.cancelledCount +
            result.timedOutCount
        result.realizedRecords.map { it.token.stableOrdinal }.sorted() shouldBeEqualTo (0 until 6).toList()
    }

    @Test
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
                adapter = object : HighContentionWorkloadAdapter {
                    override fun warmUp(identity: WorkloadIdentity) = Unit
                    override fun snapshotBaseline(): String = "baseline"
                    override fun execute(
                        token: ScheduleToken,
                        identity: WorkloadIdentity,
                    ): WorkloadTerminalDisposition = WorkloadTerminalDisposition.COMPLETED
                },
            )
        }
    }

    @Test
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
