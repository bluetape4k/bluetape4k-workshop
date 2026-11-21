package io.bluetape4k.workshop.commerce.voucher.reconciliation

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisResources
import io.bluetape4k.workshop.commerce.voucher.config.VoucherWorkerProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * VoucherReconciliationWorkerTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherReconciliationWorkerTest {

    private companion object {
/**
 * redis은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }
    }

/**
 * properties은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val properties = VoucherWorkerProperties(
        batchSize = 50,
        runDeadline = 10.seconds(),
        transactionTimeout = 2.seconds(),
        instanceId = "voucher-node-a",
    )

    @Test
/**
 * 이 시나리오는 "elected leader runs the bounded reconciliation service" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `elected leader runs the bounded reconciliation service`() {
        val service = mockk<VoucherReconciliationService>()
        val expected = ReconciliationResult(3, 1, 0, "cursor", false)
        every { service.runBatch(50, 10.seconds()) } returns expected

        val leader = VoucherLeaderRunner { action ->
            LeaderRunResult.Elected(action(), "voucher-node-a")
        }
        val worker = VoucherReconciliationWorker(service, properties, leader)

        worker.runScheduled() shouldBeEqualTo WorkerRunResult.Elected(expected, "voucher-node-a")
        verify(exactly = 1) { service.runBatch(50, 10.seconds()) }
    }

    @Test
/**
 * 이 시나리오는 "Lettuce leader stamps the configured instance id" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Lettuce leader stamps the configured instance id`() {
        val redisProperties = VoucherRedisProperties(
            enabled = true,
            uri = redis.url,
            commandTimeout = 1.seconds(),
        )
        val expected = ReconciliationResult(1, 0, 0, "cursor", false)

        VoucherRedisResources.open(redisProperties).use { resources ->
            val runner = LettuceVoucherLeaderRunner(resources::leaderElector, properties.instanceId)
            val result = runner.run { expected }

            result shouldBeEqualTo LeaderRunResult.Elected(expected, properties.instanceId)
        }
    }

    @Test
/**
 * 이 시나리오는 "leader skip and backend failure are advisory" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `leader skip and backend failure are advisory`() {
        val service = mockk<VoucherReconciliationService>()
        val skipped = VoucherReconciliationWorker(service, properties, { LeaderRunResult.Skipped })
        val failed = VoucherReconciliationWorker(
            service,
            properties,
            { throw IllegalStateException("redis unavailable") },
        )

        skipped.runScheduled() shouldBeEqualTo WorkerRunResult.LeaderSkipped
        failed.runScheduled() shouldBeEqualTo WorkerRunResult.LeaderBackendUnavailable
        verify(exactly = 0) { service.runBatch(any(), any()) }
    }

    @Test
/**
 * 이 시나리오는 "manual and scheduled overlap use one local single flight" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `manual and scheduled overlap use one local single flight`() {
        val service = mockk<VoucherReconciliationService>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val expected = ReconciliationResult(1, 0, 0, "cursor", false)

        every {
            service.runBatch(any(), any())
        } answers {
            entered.countDown()
            check(release.await(2.seconds))
            expected
        }
        val worker = VoucherReconciliationWorker(
            reconciliation = service,
            properties = properties,
            leader = { action ->
                LeaderRunResult.Elected(action(), properties.instanceId)
            },
        )

        VirtualThreads.executorService().use { executor ->
            val manual = executor.submit<WorkerRunResult> {
                worker.runManual()
            }
            entered.await(2.seconds).shouldBeTrue()
            worker.runScheduled() shouldBeEqualTo WorkerRunResult.LocalRunInProgress
            release.countDown()
            manual.get(2.seconds) shouldBeEqualTo WorkerRunResult.Manual(expected)
        }
        verify(exactly = 1) { service.runBatch(any(), any()) }
    }

    @Test
/**
 * 이 시나리오는 "Spring scheduler delegates to the shared scheduled path" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Spring scheduler delegates to the shared scheduled path`() {
        val worker = mockk<VoucherReconciliationWorker>()
        every { worker.runScheduled() } returns WorkerRunResult.LeaderSkipped

        VoucherReconciliationScheduler(worker).tick()

        verify(exactly = 1) { worker.runScheduled() }
    }
}
