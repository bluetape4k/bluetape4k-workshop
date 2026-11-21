package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.concurrent.tryAcquire
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.Semaphore
import kotlin.time.toKotlinDuration

/**
 * `DatabaseWorkload`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
enum class DatabaseWorkload {
    /**
     * `DatabaseWorkload.FOREGROUND`은 호출자 요청과 같은 foreground 경로에서 즉시 수행하는 작업입니다.
     */
    FOREGROUND,
    /**
     * `DatabaseWorkload.BACKGROUND`은 worker·scheduler가 background에서 비동기로 수행하는 작업입니다.
     */
    BACKGROUND
}

/**
 * DatabaseBulkheadOutcome은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
sealed interface DatabaseBulkheadOutcome<out T>: Serializable {
    /**
     * Executed은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data class Executed<out T>(
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val value: T,
    ): DatabaseBulkheadOutcome<T> {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID = 1L
        }
    }
/**
 * Rejected은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property workload 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Rejected(
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val workload: DatabaseWorkload,
    ): DatabaseBulkheadOutcome<Nothing> {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * Redis health와 독립적으로 bounded JDBC pool을 보호합니다.
 *
 * sweep이 interactive traffic용으로 예약된 connection headroom을 소비하지 못하도록
 * foreground command와 background worker는 의도적으로 별도 permit을 사용합니다.
 * @property acquireTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
class NodeLocalDatabaseBulkhead(
    foregroundPermits: Int = 5,
    backgroundPermits: Int = 1,
    /**
     * `acquireTimeout`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val acquireTimeout: Duration = 100.millis(),
) {
    companion object: KLogging()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val foreground = Semaphore(foregroundPermits.requirePositiveNumber("foregroundPermits"), true)
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val background = Semaphore(backgroundPermits.requirePositiveNumber("backgroundPermits"), true)

    init {
        acquireTimeout.requireZeroOrPositiveDuration("acquireTimeout")
    }

    /**
     * execute은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param workload 업무 처리에 필요한 workload 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> execute(
        workload: DatabaseWorkload,
        action: () -> T,
    ): DatabaseBulkheadOutcome<T> {
        val semaphore = when (workload) {
            DatabaseWorkload.FOREGROUND -> foreground
            DatabaseWorkload.BACKGROUND -> background
        }
        val acquired = try {
            semaphore.tryAcquire(acquireTimeout.toKotlinDuration())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
        if (!acquired) {
            log.debug { "reservation_db_bulkhead_rejected workload=$workload" }
            return DatabaseBulkheadOutcome.Rejected(workload)
        }

        return try {
            DatabaseBulkheadOutcome.Executed(action())
        } finally {
            semaphore.release()
            log.debug { "reservation_db_bulkhead_released workload=$workload" }
        }
    }
}
