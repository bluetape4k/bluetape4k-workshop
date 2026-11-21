package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.concurrent.tryAcquire
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkNull
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.toKotlinDuration

/**
 * `EventSourcedDatabaseLane`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class EventSourcedDatabaseLane {
    /**
     * `EventSourcedDatabaseLane.FOREGROUND`은 호출자 요청과 같은 foreground 경로에서 즉시 수행하는 작업입니다.
     */
    FOREGROUND,
    /**
     * `EventSourcedDatabaseLane.PROJECTION`은 `projection` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    PROJECTION,
    /**
     * `EventSourcedDatabaseLane.REBUILD`은 `rebuild` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤
     * 사용합니다.
     */
    REBUILD,
    /**
     * `EventSourcedDatabaseLane.MAINTENANCE`은 `maintenance` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    MAINTENANCE,
    /**
     * `EventSourcedDatabaseLane.READINESS`은 `readiness` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    READINESS,
}
/**
 * DatabaseBulkheadRejected은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property retryAfter 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class DatabaseBulkheadRejected(
    /**
     * `retryAfter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryAfter: Duration = RETRY_AFTER,
    cause: Throwable? = null,
): RuntimeException("DATABASE_BULKHEAD_REJECTED", cause) {
    companion object {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val RETRY_AFTER: Duration = 1.seconds()
    }
}

/**
 * `EventSourcedDatabasePermitMetrics`은 운영 작업 콘솔 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface EventSourcedDatabasePermitMetrics {
    /**
     * rejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun rejected(lane: EventSourcedDatabaseLane)

    companion object {
        val NONE = EventSourcedDatabasePermitMetrics {}
    }
}

@ConsistentCopyVisibility
/**
 * EventSourcedDatabasePermitBudget은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을
 * 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class EventSourcedDatabasePermitBudget private constructor(
    /**
     * `foreground`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val foreground: Int,
    /**
     * `projection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val projection: Int,
    /**
     * `rebuild`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val rebuild: Int,
    /**
     * `maintenance`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maintenance: Int,
    /**
     * `readiness`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val readiness: Int,
) {
    companion object {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val HIKARI_CONNECTION_BUDGET = 20
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        internal const val MAX_FOREGROUND_PARTICIPANTS = 128

        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param foreground 업무 처리에 필요한 foreground 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param rebuild 업무 처리에 필요한 rebuild 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param maintenance 업무 처리에 필요한 maintenance 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param readiness 업무 처리에 필요한 readiness 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(
            foreground: Int = 14,
            projection: Int = 3,
            rebuild: Int = 1,
            maintenance: Int = 1,
            readiness: Int = 1,
        ): EventSourcedDatabasePermitBudget {
            /**
             * `validForeground`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validForeground = foreground.requirePositiveNumber("foreground")
            /**
             * `validProjection`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validProjection = projection.requirePositiveNumber("projection")
            /**
             * `validRebuild`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validRebuild = rebuild.requirePositiveNumber("rebuild")
            /**
             * `validMaintenance`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validMaintenance = maintenance.requirePositiveNumber("maintenance")
            /**
             * `validReadiness`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validReadiness = readiness.requirePositiveNumber("readiness")
            (validForeground + validProjection + validRebuild + validMaintenance + validReadiness)
                .requireEquals(HIKARI_CONNECTION_BUDGET, "permitCapacity.sum")

            return EventSourcedDatabasePermitBudget(
                validForeground,
                validProjection,
                validRebuild,
                validMaintenance,
                validReadiness,
            )
        }
    }
}
/**
 * EventSourcedDatabasePermitSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property available 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property active 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property queued 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property rejected 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class EventSourcedDatabasePermitSnapshot(
    /**
     * `available`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val available: Int,
    /**
     * `active`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val active: Int,
    /**
     * `queued`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val queued: Int,
    /**
     * `rejected`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val rejected: Long,
)

/**
 * virtual thread가 JDBC access를 요청하기 전에 Hikari 20-connection budget을 예약합니다.
 * foreground traffic이 포화되어도 독립 readiness lane은 계속 사용할 수 있습니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class EventSourcedDatabasePermitGate(
    budget: EventSourcedDatabasePermitBudget = EventSourcedDatabasePermitBudget(),
    acquireTimeout: Duration = DEFAULT_ACQUIRE_TIMEOUT,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: EventSourcedDatabasePermitMetrics = EventSourcedDatabasePermitMetrics.NONE,
) {
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val acquireTimeout = acquireTimeout.requirePositiveDuration("acquireTimeout")
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val capacities = mapOf(
        EventSourcedDatabaseLane.FOREGROUND to budget.foreground,
        EventSourcedDatabaseLane.PROJECTION to budget.projection,
        EventSourcedDatabaseLane.REBUILD to budget.rebuild,
        EventSourcedDatabaseLane.MAINTENANCE to budget.maintenance,
        EventSourcedDatabaseLane.READINESS to budget.readiness,
    )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val semaphores = capacities.mapValues { (_, permits) -> Semaphore(permits, true) }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val heldLane = ThreadLocal<EventSourcedDatabaseLane?>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val lifecycleLock = ReentrantLock()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val drained = lifecycleLock.newCondition()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val foregroundParticipants = AtomicInteger()
    /**
     * `rejectionCounts`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val rejectionCounts = EventSourcedDatabaseLane.entries.associateWith { AtomicLong() }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    @Volatile
    private var accepting = true
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private var activePermits = 0

    /**
     * withPermit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> withPermit(
        lane: EventSourcedDatabaseLane,
        block: () -> T,
    ): T {
        val lease = acquire(lane)
        return try {
            block()
        } finally {
            lease.release()
        }
    }

    /**
     * beginShutdown은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun beginShutdown() {
        lifecycleLock.withLock {
            accepting = false
            if (activePermits == 0) drained.signalAll()
        }
    }

    /**
     * awaitDrained은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun awaitDrained(timeout: Duration): Boolean {
        val validTimeout = timeout.requireZeroOrPositiveDuration("timeout")
        var remaining = validTimeout.toNanos()

        lifecycleLock.withLock {
            while (activePermits > 0 && remaining > 0) {
                remaining = drained.awaitNanos(remaining)
            }
            return activePermits == 0
        }
    }

    /**
     * snapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun snapshot(lane: EventSourcedDatabaseLane): EventSourcedDatabasePermitSnapshot {
        val available = semaphores.getValue(lane).availablePermits()

        return EventSourcedDatabasePermitSnapshot(
            available = available,
            active = capacities.getValue(lane) - available,
            queued = semaphores.getValue(lane).queueLength,
            rejected = rejectionCounts.getValue(lane).get(),
        )
    }

    /**
     * acquire은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acquire(lane: EventSourcedDatabaseLane): PermitLease {
        ensureAccepting(lane)
        heldLane.get().checkNull { "nested database permit acquisition is forbidden" }
        val registeredForeground = registerForegroundParticipant(lane)
        val semaphore = semaphores.getValue(lane)
        val acquired = acquireSemaphore(lane, semaphore)
        if (!acquired) {
            unregisterForegroundParticipant(registeredForeground)
            throw reject(lane)
        }

        lifecycleLock.withLock {
            if (!accepting) {
                semaphore.release()
                unregisterForegroundParticipant(registeredForeground)
                throw reject(lane)
            }
            activePermits++
        }
        heldLane.set(lane)
        return PermitLease(lane, semaphore, registeredForeground)
    }

    /**
     * acquireSemaphore은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param semaphore 업무 처리에 필요한 semaphore 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acquireSemaphore(lane: EventSourcedDatabaseLane, semaphore: Semaphore): Boolean =
        try {
            semaphore.tryAcquire(acquireTimeout.toKotlinDuration())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw reject(lane, interrupted)
        }

    /**
     * registerForegroundParticipant은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은
     * revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun registerForegroundParticipant(lane: EventSourcedDatabaseLane): Boolean {
        if (lane != EventSourcedDatabaseLane.FOREGROUND) return false
        if (foregroundParticipants.incrementAndGet() <= EventSourcedDatabasePermitBudget.MAX_FOREGROUND_PARTICIPANTS) {
            return true
        }
        foregroundParticipants.decrementAndGet()
        throw reject(lane)
    }

    /**
     * unregisterForegroundParticipant은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은
     * revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registered 업무 처리에 필요한 registered 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun unregisterForegroundParticipant(registered: Boolean) {
        if (registered) foregroundParticipants.decrementAndGet()
    }

    /**
     * ensureAccepting은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun ensureAccepting(lane: EventSourcedDatabaseLane) {
        if (!accepting) throw reject(lane)
    }

    /**
     * reject은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cause 업무 처리에 필요한 cause 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun reject(
        lane: EventSourcedDatabaseLane,
        cause: Throwable? = null,
    ): DatabaseBulkheadRejected {
        rejectionCounts.getValue(lane).incrementAndGet()
        metrics.rejected(lane)
        log.debug { "event_sourced_db_permit_rejected lane=$lane" }
        return DatabaseBulkheadRejected(cause = cause)
    }
    /**
     * PermitLease은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property lane 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property semaphore 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property registeredForeground 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     */


    private inner class PermitLease(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val lane: EventSourcedDatabaseLane,
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val semaphore: Semaphore,
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val registeredForeground: Boolean,
    ) {
        /**
         * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun release() {
            heldLane.remove()
            semaphore.release()
            unregisterForegroundParticipant(registeredForeground)
            lifecycleLock.withLock {
                activePermits--
                if (activePermits == 0) drained.signalAll()
            }
            log.debug { "event_sourced_db_permit_released lane=$lane" }
        }
    }

    private companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private val DEFAULT_ACQUIRE_TIMEOUT: Duration = 250.millis()
    }
}
