package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.concurrency.TestingExecutors
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import io.bluetape4k.workshop.optimization.fieldservice.planner.PlannerInput
import io.bluetape4k.workshop.optimization.fieldservice.planner.TravelTimeMatrix
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FieldServiceReplanServiceTest {

    @Test
    fun `same aggregate coalesces onto one snapshot flight`() {
        val snapshotStarted = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val snapshotCalls = AtomicInteger(0)
        val cpuExecutor = boundedCpuExecutor()
        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                snapshotCalls.incrementAndGet()
                snapshotStarted.countDown()
                releaseSnapshot.await(5, TimeUnit.SECONDS)
                emptyInput(it)
            },
            executor = cpuExecutor,
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-same")) as ReplanAdmission.Accepted
            snapshotStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val second = service.requestReplan(AggregateId("aggregate-same")) as ReplanAdmission.Coalesced

            second.future shouldBeSameInstanceAs first.future
            releaseSnapshot.countDown()
            service.await(first).shouldNotBeNull()
            snapshotCalls.get() shouldBeEqualTo 1
        } finally {
            releaseSnapshot.countDown()
            service.close()
        }
    }

    @Test
    fun `slow snapshot does not occupy cpu planner worker`() {
        val snapshotStarted = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val cpuExecutor = boundedCpuExecutor()
        val blockingExecutor = TestingExecutors.newFixedThreadPool(2)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = { aggregateId ->
                if (aggregateId.value == "aggregate-a") {
                    snapshotStarted.countDown()
                    releaseSnapshot.await(5, TimeUnit.SECONDS)
                }
                emptyInput(aggregateId)
            },
            executor = cpuExecutor,
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-a"))
            snapshotStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val second = service.requestReplan(AggregateId("aggregate-b"))
            val secondResult = (second as ReplanAdmission.Accepted).future.get(500, TimeUnit.MILLISECONDS)
            secondResult.shouldNotBeNull()

            releaseSnapshot.countDown()
            service.await(first).shouldNotBeNull()
        } finally {
            releaseSnapshot.countDown()
            service.close()
        }
    }

    @Test
    fun `planner admission remains bounded while snapshots are pending`() {
        val releaseSnapshots = CountDownLatch(1)
        val cpuExecutor = boundedCpuExecutor()
        val blockingExecutor = TestingExecutors.newFixedThreadPool(3)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                releaseSnapshots.await(5, TimeUnit.SECONDS)
                emptyInput(it)
            },
            executor = cpuExecutor,
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            service.requestReplan(AggregateId("aggregate-a")) as ReplanAdmission.Accepted
            service.requestReplan(AggregateId("aggregate-b")) as ReplanAdmission.Accepted
            val rejected = service.requestReplan(AggregateId("aggregate-c"))

            rejected shouldBeEqualTo ReplanAdmission.Rejected("REPLAN_REJECTED")
        } finally {
            releaseSnapshots.countDown()
            service.close()
        }
    }

    @Test
    fun `snapshot timeout interrupts the task and releases the aggregate flight`() {
        val snapshotStarted = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val snapshotCalls = AtomicInteger(0)
        val cpuExecutor = boundedCpuExecutor()
        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                if (snapshotCalls.getAndIncrement() == 0) {
                    snapshotStarted.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (failure: InterruptedException) {
                        interrupted.set(true)
                        throw failure
                    } finally {
                        snapshotFinished.countDown()
                    }
                }
                emptyInput(it)
            },
            executor = cpuExecutor,
            timeout = Duration.ofMillis(100),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-timeout"))
            snapshotStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            assertFailsWith<TimeoutException> { service.await(first) }
            snapshotFinished.await(5, TimeUnit.SECONDS).shouldBeTrue()
            interrupted.get().shouldBeTrue()

            val second = service.requestReplan(AggregateId("aggregate-timeout"))
            service.await(second).shouldNotBeNull()
        } finally {
            service.close()
        }
    }

    @Test
    fun `direct future cancellation releases the aggregate flight`() {
        val snapshotStarted = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val snapshotCalls = AtomicInteger(0)
        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                if (snapshotCalls.getAndIncrement() == 0) {
                    snapshotStarted.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (failure: InterruptedException) {
                        interrupted.set(true)
                        throw failure
                    } finally {
                        snapshotFinished.countDown()
                    }
                }
                emptyInput(it)
            },
            executor = boundedCpuExecutor(),
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-cancel")) as ReplanAdmission.Accepted
            snapshotStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            first.future.cancel(true).shouldBeTrue()
            snapshotFinished.await(5, TimeUnit.SECONDS).shouldBeTrue()
            interrupted.get().shouldBeTrue()

            val second = service.requestReplan(AggregateId("aggregate-cancel"))
            service.await(second).shouldNotBeNull()
        } finally {
            service.close()
        }
    }

    @Test
    fun `non interrupting future cancellation does not interrupt a running snapshot`() {
        val snapshotStarted = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                snapshotStarted.countDown()
                try {
                    releaseSnapshot.await(5, TimeUnit.SECONDS)
                } catch (failure: InterruptedException) {
                    interrupted.set(true)
                    throw failure
                } finally {
                    snapshotFinished.countDown()
                }
                emptyInput(it)
            },
            executor = boundedCpuExecutor(),
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-cancel-no-interrupt"))
                as ReplanAdmission.Accepted
            snapshotStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            first.future.cancel(false).shouldBeTrue()
            snapshotFinished.await(100, TimeUnit.MILLISECONDS).shouldBeEqualTo(false)
            interrupted.get().shouldBeEqualTo(false)

            releaseSnapshot.countDown()
            snapshotFinished.await(5, TimeUnit.SECONDS).shouldBeTrue()
            interrupted.get().shouldBeEqualTo(false)
        } finally {
            releaseSnapshot.countDown()
            service.close()
        }
    }

    @Test
    fun `cancelled running snapshot retains admission permit until snapshot exits`() {
        val snapshotStarted = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = { aggregateId ->
                if (aggregateId.value == "aggregate-lease") {
                    snapshotStarted.countDown()
                    try {
                        releaseSnapshot.await(5, TimeUnit.SECONDS)
                    } finally {
                        snapshotFinished.countDown()
                    }
                }
                emptyInput(aggregateId)
            },
            executor = boundedCpuExecutor(),
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-lease")) as ReplanAdmission.Accepted
            snapshotStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            first.future.cancel(false).shouldBeTrue()
            snapshotFinished.await(100, TimeUnit.MILLISECONDS).shouldBeEqualTo(false)

            service.requestReplan(AggregateId("aggregate-lease-next")) as ReplanAdmission.Accepted
            service.requestReplan(AggregateId("aggregate-lease-overflow")) shouldBeEqualTo
                ReplanAdmission.Rejected("REPLAN_REJECTED")

            releaseSnapshot.countDown()
            snapshotFinished.await(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            releaseSnapshot.countDown()
            service.close()
        }
    }

    @Test
    fun `cancelled queued planner releases its lease for the next admissions`() {
        val plannerStarted = CountDownLatch(1)
        val releasePlanner = CountDownLatch(1)
        val plannerQueued = CountDownLatch(1)
        val cpuExecutor = object : ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        ) {
            override fun execute(command: Runnable) {
                super.execute(command)
                if (queue.isNotEmpty()) plannerQueued.countDown()
            }
        }
        cpuExecutor.execute {
            plannerStarted.countDown()
            releasePlanner.await(5, TimeUnit.SECONDS)
        }
        plannerStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val snapshotFinished = CountDownLatch(1)
        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                snapshotFinished.countDown()
                emptyInput(it)
            },
            executor = cpuExecutor,
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-planner-lease"))
                as ReplanAdmission.Accepted
            snapshotFinished.await(5, TimeUnit.SECONDS).shouldBeTrue()
            plannerQueued.await(5, TimeUnit.SECONDS).shouldBeTrue()

            first.future.cancel(false).shouldBeTrue()
            service.requestReplan(AggregateId("aggregate-planner-next")) as ReplanAdmission.Accepted
            service.requestReplan(AggregateId("aggregate-planner-third")) as ReplanAdmission.Accepted
        } finally {
            releasePlanner.countDown()
            service.close()
        }
    }

    @Test
    fun `rejected planner execution releases its queued stage lease`() {
        val releasePlanner = CountDownLatch(1)
        val plannerStarted = CountDownLatch(1)
        val cpuExecutor = boundedCpuExecutor()
        cpuExecutor.execute {
            plannerStarted.countDown()
            releasePlanner.await(5, TimeUnit.SECONDS)
        }
        cpuExecutor.execute { releasePlanner.await(5, TimeUnit.SECONDS) }
        plannerStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = ::emptyInput,
            executor = cpuExecutor,
            timeout = Duration.ofSeconds(2),
            blockingExecutor = blockingExecutor,
        )

        try {
            repeat(3) { index ->
                val admission = service.requestReplan(AggregateId("aggregate-rejected-$index"))
                    as ReplanAdmission.Accepted
                assertFailsWith<ExecutionException> { admission.future.get(5, TimeUnit.SECONDS) }
            }
        } finally {
            releasePlanner.countDown()
            service.close()
        }
    }

    @Test
    fun `closing the service cancels a blocked snapshot and rejects new admissions`() {
        val snapshotStarted = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val blockingExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                snapshotStarted.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (failure: InterruptedException) {
                    interrupted.set(true)
                    throw failure
                } finally {
                    snapshotFinished.countDown()
                }
                emptyInput(it)
            },
            executor = boundedCpuExecutor(),
            timeout = Duration.ofSeconds(5),
            blockingExecutor = blockingExecutor,
        )

        try {
            val first = service.requestReplan(AggregateId("aggregate-close")) as ReplanAdmission.Accepted
            snapshotStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            service.close()

            snapshotFinished.await(5, TimeUnit.SECONDS).shouldBeTrue()
            interrupted.get().shouldBeTrue()
            first.future.isCancelled.shouldBeTrue()
            service.requestReplan(AggregateId("aggregate-after-close")) shouldBeEqualTo
                ReplanAdmission.Rejected("REPLAN_REJECTED")
        } finally {
            service.close()
        }
    }

    @Test
    fun `closing the service keeps a shared blocking executor alive`() {
        val sharedExecutor = TestingExecutors.newFixedThreadPool(1)
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = ::emptyInput,
            timeout = Duration.ofSeconds(2),
            blockingExecutor = sharedExecutor,
            closeBlockingExecutor = false,
        )

        try {
            service.await(service.requestReplan(AggregateId("aggregate-shared"))).shouldNotBeNull()
            service.close()
            sharedExecutor.submit<Boolean> { true }.get(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            sharedExecutor.shutdownNow()
        }
    }

    private fun boundedCpuExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
    )

    private fun emptyInput(aggregateId: AggregateId): PlannerInput = PlannerInput(
        workers = emptyList(),
        visits = emptyList(),
        matrix = TravelTimeMatrix(0L, emptySet(), emptyMap()),
        datasetId = DatasetId("dataset-${aggregateId.value}"),
        planId = PlanId("plan-${aggregateId.value}"),
    )
}
