package io.bluetape4k.workshop.commerce.voucherpool.persistence

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * commit, rollback, close를 넘겨받지 않고 Exposed를 test-owned physical connection에 bind합니다.
 *
 * 이 raw proxy는 의도적으로 test-only입니다. lock-wait와 rollback integration test는 정확한 PostgreSQL connection을
 * 조율해야 하지만, 일반 datasource-backed Exposed transaction은 이를 fixture에 노출할 수 없습니다.
 */
@Suppress("SpreadOperator")
internal inline fun <T> withExistingJdbcTransaction(
    connection: Connection,
    crossinline block: () -> T,
): T {
    check(!connection.autoCommit) { "existing JDBC transaction helper requires auto-commit to be disabled" }

/**
 * nonOwningConnection은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
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
 * database은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val database = Database.connect(getNewConnection = { nonOwningConnection })

    return try {
        transaction(database) { block() }
    } finally {
        TransactionManager.closeAndUnregister(database)
    }
}
