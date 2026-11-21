package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.lock.LettuceLock
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CancellationException

/**
 * `SuppressionMode`은 동작 모드입니다.
 * provider 또는 실행 정책을 선택하는 값이며 테스트·운영 시나리오의 분기 기준입니다.
 */
enum class SuppressionMode {
    /**
     * `SuppressionMode.REDIS_ADVISORY`은 Redis 신호를 보조 힌트로 사용하지만 PostgreSQL durable state가 권위인 값입니다.
     */
    REDIS_ADVISORY,
    /**
     * `SuppressionMode.POSTGRES_FALLBACK`은 PostgreSQL durable state를 기준으로 보정한 값입니다.
     */
    POSTGRES_FALLBACK
}

/**
 * SuppressionOutcome은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
sealed interface SuppressionOutcome<out T>: Serializable {
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
        val mode: SuppressionMode,
    ): SuppressionOutcome<T> {
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
     * Suppressed은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Suppressed: SuppressionOutcome<Nothing>
}

/**
 * CommandSuppressionLease은 예약 제어 플레인 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
fun interface CommandSuppressionLease: AutoCloseable {
    /**
     * close은 예약 제어면의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun close()
}

/**
 * CommandSuppressionBackend은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
interface CommandSuppressionBackend {
    /** lease를 획득하면 반환하고, 다른 command가 현재 소유 중이면 `null`을 반환합니다. */
    /** lease를 획득하면 반환하고, 다른 command가 현재 소유 중이면 `null`을 반환합니다. */
    fun tryAcquire(opaqueCommandId: String): CommandSuppressionLease?
}

/**
 * Redis가 정상일 때 duplicate in-flight work를 억제하고,
 * Redis를 사용할 수 없으면 correctness를 PostgreSQL idempotency record에 위임합니다.
 * @property backend 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class InFlightCommandSuppressor(
    /**
     * `backend`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val backend: CommandSuppressionBackend? = null,
) {
    companion object: KLogging()

    /**
     * execute은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param opaqueCommandId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> execute(
        opaqueCommandId: String,
        action: () -> T,
    ): SuppressionOutcome<T> {
        val currentBackend = backend
            ?: return SuppressionOutcome.Executed(action(), SuppressionMode.POSTGRES_FALLBACK)

        val lease = try {
            currentBackend.tryAcquire(opaqueCommandId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            log.warn { "reservation_suppression_degraded reason=REDIS_UNAVAILABLE fallback=POSTGRES" }
            return SuppressionOutcome.Executed(action(), SuppressionMode.POSTGRES_FALLBACK)
        }

        if (lease == null) {
            log.debug { "reservation_suppression_hit outcome=SUPPRESSED" }
            return SuppressionOutcome.Suppressed
        }

        return try {
            SuppressionOutcome.Executed(action(), SuppressionMode.REDIS_ADVISORY)
        } finally {
            try {
                lease.close()
                log.debug { "reservation_suppression_released mode=REDIS_ADVISORY" }
            } catch (_: Exception) {
                log.warn { "reservation_suppression_release_failed reason=REDIS_UNAVAILABLE" }
            }
        }
    }
}

/**
 * `bluetape4k-lettuce:1.11.0`으로 구현한 token-checked 2초 suppression lease입니다.
 * [opaqueCommandId]는 bounded HMAC-derived identifier여야 하며, raw idempotency key나 owner token이면 안
 * 됩니다.
 * @property connection 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property keyPrefix 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property leaseTime 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 */
class LettuceLockSuppressionBackend(
    /**
     * `connection`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val connection: StatefulRedisConnection<String, String>,
    /**
     * `keyPrefix`은 예약 제어면에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val keyPrefix: String = "reservation:suppression",
    /**
     * `leaseTime`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val leaseTime: Duration = 2.seconds(),
): CommandSuppressionBackend {

    companion object: KLogging() {
        /**
         * `OPAQUE_ID`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        private val OPAQUE_ID = Regex("[A-Za-z0-9_-]{8,32}")
    }

    init {
        leaseTime.requirePositiveDuration("leaseTime")
        keyPrefix.requireNotBlank("keyPrefix")
    }

    /**
     * tryAcquire은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param opaqueCommandId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun tryAcquire(opaqueCommandId: String): CommandSuppressionLease? {
        opaqueCommandId.requireMatches(OPAQUE_ID, "opaqueCommandId")

        val lock = LettuceLock(
            connection = connection,
            lockKey = "$keyPrefix:$opaqueCommandId",
            defaultLeaseTime = leaseTime
        )
        if (!lock.tryLock(waitTime = Duration.ZERO, leaseTime = leaseTime)) {
            return null
        }
        log.debug { "reservation_suppression_acquired mode=REDIS_ADVISORY" }

        return CommandSuppressionLease {
            if (lock.isHeldByCurrentInstance()) {
                lock.unlock()
            }
        }
    }
}
