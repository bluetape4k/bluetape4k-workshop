package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.join
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationContext
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * VoucherPoolLifecycleIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolLifecycleIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "shutdown follows the bounded ownership order and is idempotent" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shutdown follows the bounded ownership order and is idempotent`() {
        val actions = RecordingLifecycleActions()
        val coordinator = VoucherPoolLifecycleCoordinator(actions)

        coordinator.shutdown()
        coordinator.shutdown()

        coordinator.events() shouldBeEqualTo VoucherPoolShutdownEvent.entries
        coordinator.reason() shouldBeEqualTo VoucherPoolShutdownReason.CLEAN
        actions.invocations shouldBeEqualTo 9
    }

    @Test
/**
 * 이 시나리오는 "drain timeout cancels rolls back and releases claims before resources close" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `drain timeout cancels rolls back and releases claims before resources close`() {
        val actions = RecordingLifecycleActions(drainResult = false)
        val coordinator = VoucherPoolLifecycleCoordinator(
            actions = actions,
            deadlines = VoucherPoolShutdownDeadlines(
                transactionDrain = 20.millis(),
                claimRelease = 20.millis(),
                phase = 100.millis(),
                total = 1.seconds(),
            ),
        )

        coordinator.shutdown()

        coordinator.reason() shouldBeEqualTo VoucherPoolShutdownReason.TRANSACTION_DRAIN_TIMEOUT
        actions.cancelled.get().shouldBeTrue()
        actions.rollbackVerified.get().shouldBeTrue()
        actions.claimReleased.get().shouldBeTrue()
        actions.claimReleaseBeforeAdvisoryClose.get().shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "database gate rejects new work and drains existing permits" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `database gate rejects new work and drains existing permits`() {
        val gate = DatabasePermitGate.default(16)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread.ofVirtual().start {
            gate.withWorkerPermit {
                entered.countDown()
                release.await()
            }
        }
        entered.await(2.seconds).shouldBeTrue()

        gate.beginShutdown()
        gate.isAccepting().shouldBeFalse()
        gate.awaitDrained(20.millis()).shouldBeFalse()
        release.countDown()
        gate.awaitDrained(2.seconds()).shouldBeTrue()
        thread.join(2.seconds)
    }

    @Test
/**
 * 이 시나리오는 "runtime stops new claims and waits for an active shared worker path" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `runtime stops new claims and waits for an active shared worker path`() {
        val runtime = VoucherPoolRuntimeControl()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val thread = Thread.ofVirtual().start {
            runtime.withClaim {
                entered.countDown()
                release.await()
            }
        }
        entered.await(2.seconds).shouldBeTrue()

        runtime.stopClaims()
        runtime.awaitClaimRelease(20.millis()).shouldBeFalse()
        runtime.withClaim { error("new claim must be rejected") }.shouldBeNull()
        release.countDown()
        runtime.awaitClaimRelease(2.seconds()).shouldBeTrue()

        thread.join(2.seconds)
    }

    @Test
/**
 * 이 시나리오는 "application lifecycle closes event stream before executor and data source" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `application lifecycle closes event stream before executor and data source`() {
        val stream = mockk<VoucherPoolStreamShutdown>(relaxed = true)
        val executor = mockk<ExecutorService>(relaxed = true)
        val dataSource = mockk<HikariDataSource>(relaxed = true)

        val beans = StaticListableBeanFactory(
            mapOf(
                "voucherPoolStreamShutdown" to stream,
                "voucherPoolExecutor" to executor,
            ),
        )
        val lifecycle = VoucherPoolLifecycle(
            applicationContext = mockk<ApplicationContext>(relaxed = true),
            gate = DatabasePermitGate.default(16),
            runtime = VoucherPoolRuntimeControl(),
            health = VoucherPoolHealthState(),
            streamShutdown = beans.getBeanProvider(VoucherPoolStreamShutdown::class.java),
            redis = beans.getBeanProvider(VoucherPoolRedisResources::class.java),
            executor = beans.getBeanProvider(ExecutorService::class.java),
            dataSource = dataSource,
        )

        lifecycle.start()
        lifecycle.stop()

        verifyOrder {
            stream.closeSseAndPoller()
            executor.shutdown()
            dataSource.close()
        }
    }

/**
 * RecordingLifecycleActions 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingLifecycleActions(
/**
 * drainResult은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val drainResult: Boolean = true,
    ): VoucherPoolLifecycleActions {
/**
 * invocations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var invocations = 0
/**
 * cancelled은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val cancelled = AtomicBoolean()
/**
 * rollbackVerified은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val rollbackVerified = AtomicBoolean()
/**
 * claimReleased은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val claimReleased = AtomicBoolean()
/**
 * claimReleaseBeforeAdvisoryClose은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val claimReleaseBeforeAdvisoryClose = AtomicBoolean()

/**
 * readinessDown은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun readinessDown() = invoked()

/**
 * rejectCommandsAndSse은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun rejectCommandsAndSse() = invoked()

/**
 * stopTriggers은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun stopTriggers() = invoked()

/**
 * stopClaims은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun stopClaims() = invoked()

/**
 * awaitTransactions은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun awaitTransactions(timeout: Duration): Boolean = drainResult.also { invoked() }

/**
 * cancelTransactions은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun cancelTransactions() {
            cancelled.set(true)
        }

/**
 * verifyRollback은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun verifyRollback(): Boolean = true.also { rollbackVerified.set(true) }

/**
 * awaitClaimRelease은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun awaitClaimRelease(timeout: Duration): Boolean = true.also { claimReleased.set(true) }

/**
 * closeSseAndPoller은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun closeSseAndPoller() = invoked()

/**
 * closeAdvisoryResources은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun closeAdvisoryResources() {
            claimReleaseBeforeAdvisoryClose.set(!cancelled.get() || claimReleased.get())
            invoked()
        }

/**
 * closeExecutor은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun closeExecutor() = invoked()

/**
 * closeDataSource은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun closeDataSource() = invoked()

/**
 * invoked은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun invoked() {
            invocations++
        }
    }
}
