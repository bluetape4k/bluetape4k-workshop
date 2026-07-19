package io.bluetape4k.workshop.commerce.voucher.reconciliation

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisResources
import io.bluetape4k.workshop.commerce.voucher.config.VoucherWorkerProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class VoucherReconciliationWorkerTest {
    private val properties =
        VoucherWorkerProperties(
            batchSize = 50,
            runDeadline = Duration.ofSeconds(10),
            transactionTimeout = Duration.ofSeconds(2),
            instanceId = "voucher-node-a",
        )

    @Test
    fun `elected leader runs the bounded reconciliation service`() {
        val service = mockk<VoucherReconciliationService>()
        val expected = ReconciliationResult(3, 1, 0, "cursor", false)
        every { service.runBatch(50, Duration.ofSeconds(10)) } returns expected
        val leader = VoucherLeaderRunner { action -> LeaderRunResult.Elected(action(), "voucher-node-a") }
        val worker = VoucherReconciliationWorker(service, properties, leader)

        worker.runScheduled() shouldBeEqualTo WorkerRunResult.Elected(expected, "voucher-node-a")
        verify(exactly = 1) { service.runBatch(50, Duration.ofSeconds(10)) }
    }

    @Test
    fun `Lettuce leader stamps the configured instance id`() {
        val redisProperties =
            VoucherRedisProperties(
                enabled = true,
                uri = redis.url,
                commandTimeout = Duration.ofSeconds(1),
            )
        val expected = ReconciliationResult(1, 0, 0, "cursor", false)

        VoucherRedisResources.open(redisProperties).use { resources ->
            val runner = LettuceVoucherLeaderRunner(resources::leaderElector, properties.instanceId)
            val result = runner.run { expected }

            result shouldBeEqualTo LeaderRunResult.Elected(expected, properties.instanceId)
        }
    }

    @Test
    fun `leader skip and backend failure are advisory`() {
        val service = mockk<VoucherReconciliationService>()
        val skipped = VoucherReconciliationWorker(service, properties, VoucherLeaderRunner { LeaderRunResult.Skipped })
        val failed =
            VoucherReconciliationWorker(
                service,
                properties,
                VoucherLeaderRunner { throw IllegalStateException("redis unavailable") },
            )

        skipped.runScheduled() shouldBeEqualTo WorkerRunResult.LeaderSkipped
        failed.runScheduled() shouldBeEqualTo WorkerRunResult.LeaderBackendUnavailable
        verify(exactly = 0) { service.runBatch(any(), any()) }
    }

    @Test
    fun `manual and scheduled overlap use one local single flight`() {
        val service = mockk<VoucherReconciliationService>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val expected = ReconciliationResult(1, 0, 0, "cursor", false)
        every { service.runBatch(any(), any()) } answers {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            expected
        }
        val worker =
            VoucherReconciliationWorker(
                service,
                properties,
                VoucherLeaderRunner { action -> LeaderRunResult.Elected(action(), properties.instanceId) },
            )

        VirtualThreads.executorService().use { executor ->
            val manual = executor.submit<WorkerRunResult> { worker.runManual() }
            check(entered.await(2, TimeUnit.SECONDS))
            worker.runScheduled() shouldBeEqualTo WorkerRunResult.LocalRunInProgress
            release.countDown()
            manual.get(2, TimeUnit.SECONDS) shouldBeEqualTo WorkerRunResult.Manual(expected)
        }
        verify(exactly = 1) { service.runBatch(any(), any()) }
    }

    @Test
    fun `Spring scheduler delegates to the shared scheduled path`() {
        val worker = mockk<VoucherReconciliationWorker>()
        every { worker.runScheduled() } returns WorkerRunResult.LeaderSkipped

        VoucherReconciliationScheduler(worker).tick()

        verify(exactly = 1) { worker.runScheduled() }
    }

    private companion object {
        val redis: RedisServer = RedisServer.Launcher.redis
    }
}
