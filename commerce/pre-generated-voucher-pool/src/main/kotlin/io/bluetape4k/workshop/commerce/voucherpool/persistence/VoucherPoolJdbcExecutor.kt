package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.admission.PoolBusyException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.util.*

/**
 * `DEFAULT_CONNECTION_ACQUISITION_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_CONNECTION_ACQUISITION_TIMEOUT_SECONDS = 2L
/**
 * `DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS = 5L
/**
 * `DEFAULT_OPERATOR_TRANSACTION_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_OPERATOR_TRANSACTION_TIMEOUT_SECONDS = 5L
/**
 * `DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS = 10L
/**
 * `DEFAULT_SSE_TRANSACTION_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_SSE_TRANSACTION_TIMEOUT_SECONDS = 5L
/**
 * `DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS = 5L
/**
 * `DEFAULT_OPERATOR_LOCK_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_OPERATOR_LOCK_TIMEOUT_SECONDS = 5L
/**
 * `DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS = 10L
/**
 * `DEFAULT_SSE_LOCK_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_SSE_LOCK_TIMEOUT_SECONDS = 5L
/**
 * `MILLIS_PER_SECOND`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MILLIS_PER_SECOND = 1_000L
/**
 * `MILLIS_CEILING_OFFSET`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val MILLIS_CEILING_OFFSET = MILLIS_PER_SECOND - 1L
/**
 * `MINIMUM_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val MINIMUM_TIMEOUT_SECONDS = 1L
/**
 * `MAX_TIMEOUT_CAUSE_DEPTH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val MAX_TIMEOUT_CAUSE_DEPTH = 32
/**
 * `POSTGRES_LOCK_NOT_AVAILABLE`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val POSTGRES_LOCK_NOT_AVAILABLE = "55P03"
/**
 * `POSTGRES_QUERY_CANCELED`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val POSTGRES_QUERY_CANCELED = "57014"

/**
 * timeoutPhaseInCauseChain은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를
 * 추정하지 않습니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun Throwable.timeoutPhaseInCauseChain(): JdbcTimeoutPhase? {
    val visited: MutableSet<Throwable?> = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    var phase: JdbcTimeoutPhase? = null
    var depth = 0

    while (current != null && depth < MAX_TIMEOUT_CAUSE_DEPTH) {
        if (phase != null || !visited.add(current)) break
        phase = current.directTimeoutPhase()
        current = current.cause
        depth++
    }
    return phase
}

/**
 * directTimeoutPhase은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
 * 않습니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun Throwable.directTimeoutPhase(): JdbcTimeoutPhase? =
    when {
        PoolBusyException::class.java.isInstance(this)              -> JdbcTimeoutPhase.PERMIT
        SQLTransientConnectionException::class.java.isInstance(this) -> JdbcTimeoutPhase.ACQUISITION
        SQLException::class.java.isInstance(this) &&
                SQLException::class.java.cast(this)
                    .sqlState == POSTGRES_LOCK_NOT_AVAILABLE        -> JdbcTimeoutPhase.LOCK

        SQLTimeoutException::class.java.isInstance(this)            -> JdbcTimeoutPhase.TRANSACTION
        SQLException::class.java.isInstance(this) &&
                SQLException::class.java
                    .cast(this).sqlState == POSTGRES_QUERY_CANCELED -> JdbcTimeoutPhase.TRANSACTION

        TransactionTimedOutException::class.java.isInstance(this)   -> JdbcTimeoutPhase.TRANSACTION

        else                                                        -> null
    }

/**
 * `JdbcExecutionLane`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class JdbcExecutionLane {
    /**
     * `JdbcExecutionLane.FOREGROUND`은 호출자 요청과 같은 foreground 경로에서 즉시 수행하는 작업입니다.
     */
    FOREGROUND,
    /**
     * `JdbcExecutionLane.OPERATOR`은 `operator` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    OPERATOR,
    /**
     * `JdbcExecutionLane.WORKER`은 `worker` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    WORKER,
    /**
     * `JdbcExecutionLane.SSE`은 `sse` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    SSE,
}

/**
 * `JdbcTimeoutPhase`은 진행 단계입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class JdbcTimeoutPhase {
    /**
     * `JdbcTimeoutPhase.ACQUISITION`은 `acquisition` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤
     * 사용합니다.
     */
    ACQUISITION,
    /**
     * `JdbcTimeoutPhase.PERMIT`은 `permit` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    PERMIT,
    /**
     * `JdbcTimeoutPhase.LOCK`은 `lock` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    LOCK,
    /**
     * `JdbcTimeoutPhase.TRANSACTION`은 `transaction` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤
     * 사용합니다.
     */
    TRANSACTION,
}
/**
 * VoucherPoolJdbcTimeouts은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property connectionAcquisition 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property foregroundTransaction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property operatorTransaction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property workerChunkTransaction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property sseTransaction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property foregroundLock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property operatorLock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property workerLock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property sseLock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class VoucherPoolJdbcTimeouts(
    /**
     * `connectionAcquisition`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val connectionAcquisition: Duration = DEFAULT_CONNECTION_ACQUISITION_TIMEOUT_SECONDS.seconds(),
    /**
     * `foregroundTransaction`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val foregroundTransaction: Duration = DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    /**
     * `operatorTransaction`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operatorTransaction: Duration = DEFAULT_OPERATOR_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    /**
     * `workerChunkTransaction`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val workerChunkTransaction: Duration = DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    /**
     * `sseTransaction`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sseTransaction: Duration = DEFAULT_SSE_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    /**
     * `foregroundLock`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val foregroundLock: Duration = DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS.seconds(),
    /**
     * `operatorLock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operatorLock: Duration = DEFAULT_OPERATOR_LOCK_TIMEOUT_SECONDS.seconds(),
    /**
     * `workerLock`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val workerLock: Duration = DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS.seconds(),
    /**
     * `sseLock`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sseLock: Duration = DEFAULT_SSE_LOCK_TIMEOUT_SECONDS.seconds(),
) {
    init {
        require(
            listOf(
                connectionAcquisition,
                foregroundTransaction,
                operatorTransaction,
                workerChunkTransaction,
                sseTransaction,
                foregroundLock,
                operatorLock,
                workerLock,
                sseLock,
            ).all { !it.isNegative && !it.isZero },
        ) { "JDBC timeouts must be positive" }

        require(listOf(foregroundLock, operatorLock, workerLock, sseLock).all { it.toMillis() >= 1L }) {
            "JDBC lock timeouts must be at least one millisecond"
        }
    }

    /**
     * transactionTimeout은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transactionTimeout(lane: JdbcExecutionLane): Duration = when (lane) {
        JdbcExecutionLane.FOREGROUND -> foregroundTransaction
        JdbcExecutionLane.OPERATOR   -> operatorTransaction
        JdbcExecutionLane.WORKER     -> workerChunkTransaction
        JdbcExecutionLane.SSE        -> sseTransaction
    }

    /**
     * lockTimeout은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun lockTimeout(lane: JdbcExecutionLane): Duration = when (lane) {
        JdbcExecutionLane.FOREGROUND -> foregroundLock
        JdbcExecutionLane.OPERATOR   -> operatorLock
        JdbcExecutionLane.WORKER     -> workerLock
        JdbcExecutionLane.SSE        -> sseLock
    }
}
/**
 * VoucherPoolJdbcTimeoutException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property lane 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property phase 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class VoucherPoolJdbcTimeoutException(
    /**
     * `lane`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lane: JdbcExecutionLane,
    /**
     * `phase`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val phase: JdbcTimeoutPhase,
    cause: Throwable,
): RuntimeException("voucher pool JDBC timeout lane=$lane phase=$phase", cause)

/**
 * VoucherPoolJdbcMetrics은 사전 생성 바우처 풀 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherPoolJdbcMetrics {
    /**
     * timedOut은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param phase 업무 처리에 필요한 phase 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun timedOut(
        lane: JdbcExecutionLane,
        phase: JdbcTimeoutPhase,
    )

    companion object {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val NONE = VoucherPoolJdbcMetrics { _, _ -> }
    }
}

/**
 * VoucherPoolLockTimeoutApplier은 사전 생성 바우처 풀 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherPoolLockTimeoutApplier {

    /**
     * apply은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun apply(timeout: Duration)

    companion object {
        val NONE = VoucherPoolLockTimeoutApplier {}
    }
}

/**
 * admission 이후, Spring transaction 안에서만 JDBC/Exposed work를 실행합니다.
 * @property gate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property transactionManager 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property timeouts 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property lockTimeoutApplier 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
internal class VoucherPoolJdbcExecutor(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
    /**
     * `transactionManager`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactionManager: PlatformTransactionManager,
    /**
     * `timeouts`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val timeouts: VoucherPoolJdbcTimeouts = VoucherPoolJdbcTimeouts(),
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherPoolJdbcMetrics = VoucherPoolJdbcMetrics.NONE,
    /**
     * `lockTimeoutApplier`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val lockTimeoutApplier: VoucherPoolLockTimeoutApplier = VoucherPoolLockTimeoutApplier.NONE,
) {
    /**
     * foregroundTransaction은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> foregroundTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.FOREGROUND, block)

    /**
     * operatorTransaction은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> operatorTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.OPERATOR, block)

    /**
     * workerTransaction은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> workerTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.WORKER, block)

    /**
     * sseTransaction은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> sseTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.SSE, block)

    /**
     * afterCommit은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun afterCommit(action: () -> Unit) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "afterCommit requires active transaction synchronization"
        }
        TransactionSynchronizationManager.registerSynchronization(
            object: TransactionSynchronization {
                /**
                 * afterCommit은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
                 * 필드를 함께 보존합니다.
                 *
                 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
                 */
                override fun afterCommit() = action()
            },
        )
    }

    /**
     * execute은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private inline fun <T> execute(
        lane: JdbcExecutionLane,
        crossinline block: () -> T,
    ): T =
        try {
            withPermit(lane) {
                /**
                 * `template`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
                 *
                 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
                 */
                val template = TransactionTemplate(transactionManager)
                    .apply {
                        timeout = timeouts.transactionTimeout(lane).toSpringTimeoutSeconds()
                    }
                /**
                 * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val result = template.execute {
                    lockTimeoutApplier.apply(timeouts.lockTimeout(lane))
                    TransactionResult(block())
                } // .checkNotNull { "Lock timeouts must not be null" }
                log.debug { "voucher_pool_jdbc_transaction_completed lane=$lane, result=$result" }

                result.value
            }
        } catch (failure: PoolBusyException) {
            throw timeoutFailure(lane, JdbcTimeoutPhase.PERMIT, failure)
        } catch (failure: VoucherPoolJdbcTimeoutException) {
            throw failure
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            throw failure.toTimeoutFailure(lane) ?: failure
        }

    /**
     * withPermit은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> withPermit(
        lane: JdbcExecutionLane,
        block: () -> T,
    ): T =
        when (lane) {
            JdbcExecutionLane.FOREGROUND,
            JdbcExecutionLane.OPERATOR,
                                  -> gate.withForegroundPermit(block)

            JdbcExecutionLane.WORKER -> gate.withWorkerPermit(block)
            JdbcExecutionLane.SSE -> gate.withSsePermit(block)
        }

    /**
     * timeoutFailure은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param phase 업무 처리에 필요한 phase 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cause 업무 처리에 필요한 cause 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun timeoutFailure(
        lane: JdbcExecutionLane,
        phase: JdbcTimeoutPhase,
        cause: Throwable,
    ): VoucherPoolJdbcTimeoutException {
        metrics.timedOut(lane, phase)
        log.debug { "voucher_pool_jdbc_timeout lane=$lane phase=$phase" }
        return VoucherPoolJdbcTimeoutException(lane = lane, phase = phase, cause = cause)
    }

    /**
     * toTimeoutFailure은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun Throwable.toTimeoutFailure(lane: JdbcExecutionLane): VoucherPoolJdbcTimeoutException? =
        timeoutPhaseInCauseChain()?.let { phase ->
            timeoutFailure(lane, phase, this)
        }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private data class TransactionResult<T>(val value: T)

    /**
     * toSpringTimeoutSeconds은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun Duration.toSpringTimeoutSeconds(): Int =
        ((toMillis() + MILLIS_CEILING_OFFSET) / MILLIS_PER_SECOND)
            .coerceAtLeast(MINIMUM_TIMEOUT_SECONDS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    companion object: KLogging()
}
