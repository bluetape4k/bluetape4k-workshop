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
internal fun <T> withExistingJdbcTransaction(connection: Connection, block: () -> T): T {
    check(!connection.autoCommit) { "existing JDBC transaction helper requires auto-commit to be disabled" }
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
    val database = Database.connect(getNewConnection = { nonOwningConnection })
    return try {
        transaction(database) { block() }
    } finally {
        TransactionManager.closeAndUnregister(database)
    }
}
