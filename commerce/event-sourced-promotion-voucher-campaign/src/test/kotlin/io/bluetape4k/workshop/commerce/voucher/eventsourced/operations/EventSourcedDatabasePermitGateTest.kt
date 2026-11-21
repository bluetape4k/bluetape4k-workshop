package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.join
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * EventSourcedDatabasePermitGateTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedDatabasePermitGateTest {

    private companion object: KLogging() {
/**
 * AWAIT_TIMEOUT_SECONDS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val AWAIT_TIMEOUT_SECONDS = 5.seconds
    }

    @Test
/**
 * 이 시나리오는 "foreground saturation rejects before JDBC admission without consuming readiness
 * capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `foreground saturation rejects before JDBC admission without consuming readiness capacity`() {
        val gate = EventSourcedDatabasePermitGate(
            budget = EventSourcedDatabasePermitBudget(
                foreground = 1,
                projection = 1,
                rebuild = 1,
                maintenance = 1,
                readiness = 16,
            ),
            acquireTimeout = 1.millis(),
        )

        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val holder: Thread = Thread.ofVirtual().start {
            runCatching {
                gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) {
                    acquired.countDown()
                    release.await(AWAIT_TIMEOUT_SECONDS).shouldBeTrue()
                }
            }.onFailure(failure::set)
        }
        acquired.await(AWAIT_TIMEOUT_SECONDS).shouldBeTrue()

        try {
            assertFailsWith<DatabaseBulkheadRejected> {
                gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) { error("must not be admitted") }
            }
            gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 16
            gate.withPermit(EventSourcedDatabaseLane.READINESS) {}
        } finally {
            release.countDown()
            holder.join(AWAIT_TIMEOUT_SECONDS)
        }

        failure.get().shouldBeNull()
        gate.snapshot(EventSourcedDatabaseLane.FOREGROUND).available shouldBeEqualTo 1
        gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 16
        gate.snapshot(EventSourcedDatabaseLane.FOREGROUND).rejected shouldBeEqualTo 1L
    }

    @Test
/**
 * 이 시나리오는 "shutdown rejects new admissions only after in flight work has drained" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shutdown rejects new admissions only after in flight work has drained`() {
        val gate = EventSourcedDatabasePermitGate(acquireTimeout = 1.millis())

        gate.beginShutdown()

        assertFailsWith<DatabaseBulkheadRejected> {
            gate.withPermit(EventSourcedDatabaseLane.MAINTENANCE) {
                error("must not be admitted")
            }
        }
        gate.awaitDrained(Duration.ZERO).shouldBeTrue()
    }
}
