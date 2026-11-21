package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.semaphore.LettuceSemaphore
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.api.StatefulRedisConnection
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.LockSupport

/**
 * `AdmissionMode`은 동작 모드입니다.
 * provider 또는 실행 정책을 선택하는 값이며 테스트·운영 시나리오의 분기 기준입니다.
 */
enum class AdmissionMode {
    /**
     * `AdmissionMode.REDIS_ADVISORY`은 Redis 신호를 보조 힌트로 사용하지만 PostgreSQL durable state가 권위인 값입니다.
     */
    REDIS_ADVISORY,
    /**
     * `AdmissionMode.LOCAL_FALLBACK`은 공유 backend 대신 노드 로컬 보정 경로를 사용한 값입니다.
     */
    LOCAL_FALLBACK
}

/**
 * `AdmissionRejection`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
enum class AdmissionRejection {
    /**
     * `AdmissionRejection.LOCAL_CAPACITY`은 노드 로컬 capacity 제한을 나타내는 유형입니다.
     */
    LOCAL_CAPACITY,
    /**
     * `AdmissionRejection.REDIS_CAPACITY`은 Redis admission capacity 제한을 나타내는 유형입니다.
     */
    REDIS_CAPACITY
}

/**
 * AdmissionOutcome은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
sealed interface AdmissionOutcome<out T>: Serializable {
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
        /**
         * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val mode: AdmissionMode,
    ): AdmissionOutcome<T> {
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
 * @property reason 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Rejected(
        /**
         * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val reason: AdmissionRejection,
    ): AdmissionOutcome<Nothing> {
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
 * AdmissionPermitBackend은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
interface AdmissionPermitBackend {
    /**
     * tryAcquire은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param waitTime 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun tryAcquire(waitTime: Duration): Boolean

    /**
     * release은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun release()
}

/**
 * optional distributed advisory permit 전에 항상 켜져 있는 local JDBC bulkhead를 적용합니다.
 * Redis가 없거나 사용할 수 없을 때도 PostgreSQL이 correctness authority로 남습니다.
 * @property localBulkhead 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property redisBackend 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property redisWaitTime 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 */
class ReservationAdmissionGate(
    /**
     * `localBulkhead`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val localBulkhead: NodeLocalDatabaseBulkhead,
    /**
     * `redisBackend`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val redisBackend: AdmissionPermitBackend? = null,
    /**
     * `redisWaitTime`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val redisWaitTime: Duration = 100.millis(),
) {
    companion object: KLogging()

    init {
        redisWaitTime.requireZeroOrPositiveDuration("redisWaitTime")
    }

    /**
     * execute은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> execute(action: () -> T): AdmissionOutcome<T> {
        val local = localBulkhead.execute(DatabaseWorkload.FOREGROUND) {
            executeAfterLocalAcquire(action)
        }

        return when (local) {
            is DatabaseBulkheadOutcome.Executed -> local.value
            is DatabaseBulkheadOutcome.Rejected -> AdmissionOutcome.Rejected(AdmissionRejection.LOCAL_CAPACITY)
        }
    }

    /**
     * executeAfterLocalAcquire은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> executeAfterLocalAcquire(action: () -> T): AdmissionOutcome<T> {
        val backend = redisBackend
            ?: return AdmissionOutcome.Executed(action(), AdmissionMode.LOCAL_FALLBACK)

        val redisAcquired = try {
            backend.tryAcquire(redisWaitTime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            log.warn { "reservation_admission_degraded reason=REDIS_UNAVAILABLE fallback=LOCAL" }
            return AdmissionOutcome.Executed(action(), AdmissionMode.LOCAL_FALLBACK)
        }

        if (!redisAcquired) {
            log.debug { "reservation_admission_rejected reason=REDIS_CAPACITY" }
            return AdmissionOutcome.Rejected(AdmissionRejection.REDIS_CAPACITY)
        }

        return try {
            AdmissionOutcome.Executed(action(), AdmissionMode.REDIS_ADVISORY)
        } finally {
            try {
                backend.release()
                log.debug { "reservation_admission_released mode=REDIS_ADVISORY" }
            } catch (_: Exception) {
                // Redis는 advisory입니다. release failure가 authoritative DB outcome을 대체하면 안 됩니다.
                log.warn { "reservation_admission_release_failed reason=REDIS_UNAVAILABLE" }
            }
        }
    }
}

/**
 * published `bluetape4k-lettuce:1.11.0` semaphore API용 adapter입니다.
 *
 * version 1.11.0에는 expiring permit/owner lease가 없습니다. 따라서 caller는 `finally`에서 release해야 하고,
 * 이 semaphore를 reservation capacity authority로 사용하면 안 되며, Redis reset을 견뎌야 합니다.
 * @property retryInterval 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
class LettuceSemaphoreAdmissionBackend(
    connection: StatefulRedisConnection<String, String>,
    semaphoreKey: String = "reservation:admission",
    totalPermits: Int = 64,
    /**
     * `retryInterval`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val retryInterval: Duration = 10.millis(),
): AdmissionPermitBackend {
    companion object: KLogging()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val semaphore = LettuceSemaphore(
        connection = connection,
        semaphoreKey = semaphoreKey,
        totalPermits = totalPermits.requirePositiveNumber("totalPermits")
    )

    init {
        retryInterval.requirePositiveDuration("interval")
    }

    /**
     * tryAcquire은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param waitTime 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun tryAcquire(waitTime: Duration): Boolean {
        waitTime.requireZeroOrPositiveDuration("waitTime")

        // SET NX는 idempotent이며 Redis flush/restart 뒤 advisory counter도 복원합니다.
        semaphore.initialize()
        val deadline = System.nanoTime() + waitTime.toNanos()

        do {
            if (semaphore.tryAcquire()) {
                log.debug { "reservation_admission_acquired mode=REDIS_ADVISORY" }
                return true
            }
            if (System.nanoTime() < deadline) {
                LockSupport.parkNanos(retryInterval.toNanos())
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("interrupted while waiting for advisory admission")
                }
            }
        } while (System.nanoTime() < deadline)

        return false
    }

    /**
     * release은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun release() {
        semaphore.release()
    }
}
