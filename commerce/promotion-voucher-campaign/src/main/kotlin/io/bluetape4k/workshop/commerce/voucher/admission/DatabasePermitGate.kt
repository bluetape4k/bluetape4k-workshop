package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * DatabaseLane은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class DatabaseLane {
    /**
     * `DatabaseLane.FOREGROUND`은 DatabaseLane 업무 상태의 `FOREGROUND` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를
     * 선택하는
     * 기준으로 사용합니다.
     */
    FOREGROUND,
    /**
     * `DatabaseLane.WORKER`은 DatabaseLane 업무 상태의 `WORKER` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    WORKER,
    /**
     * `DatabaseLane.SSE_MAINTENANCE`은 DatabaseLane 업무 상태의 `SSE_MAINTENANCE` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    SSE_MAINTENANCE,
}

/**
 * DatabasePermitRejected은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class DatabasePermitRejected(
    /**
     * `retryAfter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryAfter: Duration,
    cause: Throwable? = null,
): RuntimeException("database permit unavailable", cause)

/**
 * DatabasePermitMetrics은 프로모션 바우처 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface DatabasePermitMetrics {
    /**
     * rejected은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param lane 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun rejected(lane: DatabaseLane)

    companion object {
        val NONE = DatabasePermitMetrics {}
    }
}

/**
 * virtual-thread concurrency를 bounded JDBC connection pool 바깥에서 제어합니다.
 *
 * 각 lane은 fair semaphore를 소유하므로 interactive traffic, reconciliation worker, SSE maintenance가
 * 서로의 예약된 database capacity를 소비하지 못합니다.
 */
internal class DatabasePermitGate(
    foregroundPermits: Int = 12,
    workerPermits: Int = 1,
    sseMaintenancePermits: Int = 3,
    /**
     * `acquireTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val acquireTimeout: Duration = 250.millis(),
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: DatabasePermitMetrics = DatabasePermitMetrics.NONE,
) {
    companion object: KLogging() {
        /**
         * `RETRY_AFTER`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val RETRY_AFTER: Duration = 1.seconds()
    }

    /**
     * `permitCapacities`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val permitCapacities = mapOf(
        DatabaseLane.FOREGROUND to foregroundPermits.requirePositiveNumber("foregroundPermits"),
        DatabaseLane.WORKER to workerPermits.requirePositiveNumber("workerPermits"),
        DatabaseLane.SSE_MAINTENANCE to sseMaintenancePermits.requirePositiveNumber("sseMaintenancePermits"),
    )

    /**
     * `semaphores`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val semaphores: Map<DatabaseLane, Semaphore> = permitCapacities.mapValues { (_, permits) ->
        Semaphore(permits, true)
    }
    /**
     * `heldLane`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val heldLane = ThreadLocal<DatabaseLane?>()
    /**
     * `lifecycleLock`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val lifecycleLock = ReentrantLock()
    /**
     * `drained`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val drained = lifecycleLock.newCondition()

    @Volatile
    /**
     * `accepting`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var accepting = true

    /**
     * `activePermits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var activePermits = 0

    init {
        require(!acquireTimeout.isNegative && !acquireTimeout.isZero) {
            "acquireTimeout must be positive"
        }
    }

/**
 * withPermit은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> withPermit(
        lane: DatabaseLane,
        block: () -> T,
    ): T {
        if (!accepting) throw DatabasePermitRejected(RETRY_AFTER)
        check(heldLane.get() == null) { "nested database permit acquisition is forbidden" }

        val semaphore = semaphores.getValue(lane)
        val acquired = try {
            semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DatabasePermitRejected(RETRY_AFTER, interrupted)
        }

        if (!acquired) {
            metrics.rejected(lane)
            log.debug { "voucher_db_permit_rejected lane=$lane" }
            throw DatabasePermitRejected(RETRY_AFTER)
        }

        lifecycleLock.withLock {
            if (!accepting) {
                semaphore.release()
                metrics.rejected(lane)
                throw DatabasePermitRejected(RETRY_AFTER)
            }
            activePermits++
        }

        heldLane.set(lane)

        return try {
            block()
        } finally {
            heldLane.remove()
            semaphore.release()
            lifecycleLock.withLock {
                activePermits--
                if (activePermits == 0) drained.signalAll()
            }
            log.debug { "voucher_db_permit_released lane=$lane" }
        }
    }

    /** 이미 admission된 work는 drain되도록 두면서 이후 acquisition을 atomic하게 거부합니다. */
    /** 이미 admission된 work는 drain되도록 두면서 이후 acquisition을 atomic하게 거부합니다. */
    fun beginShutdown() {
        lifecycleLock.withLock {
            accepting = false
            if (activePermits == 0) drained.signalAll()
        }
    }

    /**
     * awaitDrained은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param timeout 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun awaitDrained(timeout: Duration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        var remaining = timeout.toNanos()

        lifecycleLock.withLock {
            while (activePermits > 0 && remaining > 0) {
                remaining = drained.awaitNanos(remaining)
            }
            return activePermits == 0
        }
    }

    /**
     * inUsePermits은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun inUsePermits(): Int = lifecycleLock.withLock { activePermits }

    /**
     * requireHeld은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun requireHeld() {
        check(heldLane.get() != null) { "JDBC access requires a database permit" }
    }

    /** transaction facade가 permit을 다시 얻지 않고 자신의 foreground boundary에 참여할 수 있게 합니다. */
    /** transaction facade가 permit을 다시 얻지 않고 자신의 foreground boundary에 참여할 수 있게 합니다. */
    fun isHeld(lane: DatabaseLane): Boolean =
        heldLane.get() == lane

    /** health probe와 결정적 leak test를 위해 lane-local capacity를 노출합니다. */
    /** health probe와 결정적 leak test를 위해 lane-local capacity를 노출합니다. */
    fun availablePermits(lane: DatabaseLane): Int =
        semaphores.getValue(lane).availablePermits()

    /** permit을 얻지 않고 lane-local occupancy를 노출합니다. */
    /** permit을 얻지 않고 lane-local occupancy를 노출합니다. */
    fun inUsePermits(lane: DatabaseLane): Int =
        permitCapacities.getValue(lane) - availablePermits(lane)

    /** bounded operational sampling을 위해 fair semaphore queue depth를 노출합니다. */
    /** bounded operational sampling을 위해 fair semaphore queue depth를 노출합니다. */
    fun waitingThreads(lane: DatabaseLane): Int =
        semaphores.getValue(lane).queueLength

}
