package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.concurrent.tryAcquire
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.support.requirePositiveNumber
import okio.withLock
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serial
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.time.toKotlinDuration

/** 모든 ticket transaction에 적용되는 전역 row-lock 획득 순서입니다. */
/** 모든 ticket transaction에 적용되는 전역 row-lock 획득 순서입니다. */
enum class TicketLockRank {
    /**
     * `TicketLockRank.SALE`은 판매 aggregate를 가리키는 유형입니다.
     */
    SALE,
    /**
     * `TicketLockRank.IDEMPOTENCY`은 동일 key 재전송을 하나의 논리 요청으로 묶는 정책 대상입니다.
     */
    IDEMPOTENCY,
    /**
     * `TicketLockRank.USER_GUARD`은 사용자별 중복·한도 admission guard입니다.
     */
    USER_GUARD,
    /**
     * `TicketLockRank.IP_GUARD`은 IP별 속도·남용 admission guard입니다.
     */
    IP_GUARD,
    /**
     * `TicketLockRank.BUYER`은 고객 주체 기준의 scope 또는 식별 유형입니다.
     */
    BUYER,
    /**
     * `TicketLockRank.INVENTORY`은 재고·capacity 자원 기준의 유형입니다.
     */
    INVENTORY,
    /**
     * `TicketLockRank.ATTEMPT_ORDER`은 purchase 시도의 순서를 나타내는 정렬 기준입니다.
     */
    ATTEMPT_ORDER,
    /**
     * `TicketLockRank.EFFECT`은 외부 side effect와 durable receipt를 묶는 유형입니다.
     */
    EFFECT,
}

/**
 * 역순 deadlock을 만들 수 있는 code path를 거부합니다.
 * @property previous 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property requested 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
class TicketLockOrderViolation(
    /**
     * `previous`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val previous: TicketLockRank,
    /**
     * `requested`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val requested: TicketLockRank,
): IllegalStateException("ticket_lock_order_violation:$previous:$requested") {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** JDBC connection을 얻기 전 database work를 거부합니다. */
/** JDBC connection을 얻기 전 database work를 거부합니다. */
class TicketDatabasePermitUnavailable: IllegalStateException("ticket_database_permit_unavailable") {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** lock 획득 순서를 기록하는 단일 Exposed transaction입니다. */
/** lock 획득 순서를 기록하는 단일 Exposed transaction입니다. */
class TicketJdbcTransaction internal constructor(
    /**
     * `transaction`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val transaction: JdbcTransaction,
) {
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var lastRank: TicketLockRank? = null
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val lock = ReentrantLock()

    /**
     * acquire은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rank 업무 처리에 필요한 rank 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun acquire(rank: TicketLockRank) {
        lock.withLock {
            val previous = lastRank
            if (previous != null && rank < previous) {
                throw TicketLockOrderViolation(previous, rank)
            }
            lastRank = rank
        }
    }
}

/**
 * Exposed JDBC transaction을 열기 전에 bounded permit을 획득합니다.
 * @property permitTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
class TicketJdbcExecutor(
    dataSource: DataSource,
    foregroundPermits: Int,
    /**
     * `permitTimeout`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    internal val permitTimeout: Duration = 250.millis(),
): AutoCloseable {
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val permits = Semaphore(foregroundPermits, true)
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val closed = AtomicBoolean()
    /**
     * 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */

    private val database = Database.connect(
        datasource = dataSource,
        databaseConfig = DatabaseConfig {
            defaultMaxAttempts = 1
        },
    )

    init {
        foregroundPermits.requirePositiveNumber("foregroundPermits")
        permitTimeout.requirePositiveDuration("permitTimeout")
    }

    /**
     * transaction은 콘서트 티켓 판매 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> transaction(block: TicketJdbcTransaction.() -> T): T {
        check(!closed.get()) { "ticket_jdbc_executor_closed" }

        if (!permits.tryAcquire(permitTimeout.toKotlinDuration())) {
            throw TicketDatabasePermitUnavailable()
        }

        try {
            return transaction(database) {
                TicketJdbcTransaction(this).block()
            }
        } finally {
            permits.release()
        }
    }

    /**
     * close은 콘서트 티켓 판매의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            TransactionManager.closeAndUnregister(database)
        }
    }
}
