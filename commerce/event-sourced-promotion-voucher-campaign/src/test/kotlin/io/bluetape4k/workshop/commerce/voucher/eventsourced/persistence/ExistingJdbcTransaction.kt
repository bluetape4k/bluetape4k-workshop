package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.statements.api.ResultApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection

/** 이미 열린 test-owned JDBC transaction에 Exposed query를 연결합니다. */
/** 이미 열린 test-owned JDBC transaction에 Exposed query를 연결합니다. */
@Suppress("SpreadOperator")
internal inline fun <T> withExistingJdbcTransaction(
    connection: Connection,
    crossinline block: () -> T,
): T {
    check(!connection.autoCommit) { "existing JDBC transaction helper requires auto-commit to be disabled" }

/**
 * nonOwningConnection은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val nonOwningConnection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "close", "commit", "rollback", "abort", "setAutoCommit", "setReadOnly", "setTransactionIsolation",
            "setCatalog", "setSchema", "setNetworkTimeout",
                -> null
            else -> try {
                method.invoke(connection, *arguments.orEmpty())
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    } as Connection

/**
 * existingDatabase은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val existingDatabase = Database.connect(getNewConnection = { nonOwningConnection })
    return try {
        transaction(existingDatabase) { block() }
    } finally {
        TransactionManager.closeAndUnregister(existingDatabase)
    }
}

/**
 * rawSelect은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun rawSelect(sql: String): Statement<*> = RawSelectStatement(sql)

/**
 * RawSelectStatement 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class RawSelectStatement(
/**
 * sql은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val sql: String,
): Statement<ResultApi>(StatementType.SELECT, emptyList()) {
/**
 * prepareSQL은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = sql

/**
 * arguments은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> = emptyList()
}
