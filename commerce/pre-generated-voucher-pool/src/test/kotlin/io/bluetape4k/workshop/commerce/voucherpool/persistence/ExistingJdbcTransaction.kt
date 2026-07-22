package io.bluetape4k.workshop.commerce.voucherpool.persistence

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * Binds Exposed to a test-owned physical connection without taking over commit, rollback, or close.
 *
 * This raw proxy is intentionally test-only: lock-wait and rollback integration tests must coordinate exact
 * PostgreSQL connections, which a normal datasource-backed Exposed transaction cannot expose to the fixture.
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
