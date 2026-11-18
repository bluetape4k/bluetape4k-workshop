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
@Suppress("SpreadOperator")
internal inline fun <T> withExistingJdbcTransaction(
    connection: Connection,
    crossinline block: () -> T,
): T {
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

    val existingDatabase = Database.connect(getNewConnection = { nonOwningConnection })
    return try {
        transaction(existingDatabase) { block() }
    } finally {
        TransactionManager.closeAndUnregister(existingDatabase)
    }
}

internal fun rawSelect(sql: String): Statement<*> = RawSelectStatement(sql)

private class RawSelectStatement(
    private val sql: String,
): Statement<ResultApi>(StatementType.SELECT, emptyList()) {
    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = sql

    override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> = emptyList()
}
