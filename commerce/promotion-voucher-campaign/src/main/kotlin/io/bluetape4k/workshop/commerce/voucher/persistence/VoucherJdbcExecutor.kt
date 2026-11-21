package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * VoucherTransactionRunner은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface VoucherTransactionRunner {
/**
 * foregroundTransaction은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> foregroundTransaction(block: () -> T): T
}

/** 일치하는 local JDBC permit을 보유한 뒤에만 Spring/Exposed transaction을 엽니다. */
/** 일치하는 local JDBC permit을 보유한 뒤에만 Spring/Exposed transaction을 엽니다. */
internal class VoucherJdbcExecutor(
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
     * `lockTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val lockTimeout: Duration = 5.seconds(),
): VoucherTransactionRunner {

    companion object: KLogging()

    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions = TransactionTemplate(transactionManager)

/**
 * foregroundTransaction은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    override fun <T> foregroundTransaction(block: () -> T): T =
        if (gate.isHeld(DatabaseLane.FOREGROUND)) {
            block()
        } else {
            transaction(DatabaseLane.FOREGROUND, applyLockTimeout = true, block = block)
        }

/**
 * workerTransaction은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> workerTransaction(block: () -> T): T =
        transaction(DatabaseLane.WORKER, applyLockTimeout = false, block = block)

    /** deadline-bounded worker row에 Spring timeout과 PostgreSQL timeout을 모두 적용합니다. */
    /** deadline-bounded worker row에 Spring timeout과 PostgreSQL timeout을 모두 적용합니다. */
    fun <T> workerTransaction(
        timeout: Duration,
        block: () -> T,
    ): T {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }

        return transaction(
            lane = DatabaseLane.WORKER,
            applyLockTimeout = false,
            transactionTimeout = timeout,
            block = block,
        )
    }

/**
 * sseMaintenanceTransaction은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> sseMaintenanceTransaction(block: () -> T): T =
        transaction(DatabaseLane.SSE_MAINTENANCE, applyLockTimeout = false, block = block)

/**
 * transaction은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private inline fun <T> transaction(
        lane: DatabaseLane,
        applyLockTimeout: Boolean,
        transactionTimeout: Duration? = null,
        crossinline block: () -> T,
    ): T =
        gate.withPermit(lane) {
            /**
             * `template`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val template = transactionTimeout?.let { timeout ->
                TransactionTemplate(transactionManager).apply {
                    this.timeout = timeout.toSpringTimeoutSeconds()
                }
            } ?: transactions

            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = template.execute {
                if (applyLockTimeout) {
                    TransactionManager.current()
                        .exec("SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'")
                }
                if (transactionTimeout != null) {
                    TransactionManager.current()
                        .exec(
                            "SET LOCAL statement_timeout = '${transactionTimeout.toMillis().coerceAtLeast(1)}ms'",
                        )
                }
                TransactionResult(block())
            }

            log.debug { "voucher_jdbc_transaction_completed lane=$lane" }
            result.value
        }

    /**
     * TransactionResult은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class TransactionResult<T>(val value: T)

/**
 * toSpringTimeoutSeconds은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun Duration.toSpringTimeoutSeconds(): Int =
        ((toMillis() + 999L) / 1_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}
