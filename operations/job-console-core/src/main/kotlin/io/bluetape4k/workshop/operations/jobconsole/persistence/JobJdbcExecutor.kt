package io.bluetape4k.workshop.operations.jobconsole.persistence

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/**
 * job-console 저장소가 공유하는 Exposed JDBC transaction 경계입니다.
 *
 * PostgreSQL advisory lock, `SKIP LOCKED`, `RETURNING`처럼 제어성이 필요한 SQL도
 * 이 경계 안에서 실행하며, connection 수명과 commit/rollback은 저장소가 직접
 * 관리하지 않고 Exposed에 위임합니다.
 */
internal class JobJdbcExecutor(
    dataSource: DataSource,
) : AutoCloseable {
    private val database = Database.connect(
        datasource = dataSource,
        databaseConfig = DatabaseConfig {
            defaultMaxAttempts = 1
        },
    )

    fun <T> transaction(block: (Connection) -> T): T = transaction(database) {
        block(connection.connection as Connection)
    }

    fun <T> exposedTransaction(block: JdbcTransaction.() -> T): T = transaction(database) {
        block()
    }

    /**
     * 이미 획득한 connection도 Exposed transaction으로 감쌉니다.
     * Exposed가 transaction 종료 시 호출하는 close는 proxy에서 차단해 caller 소유권을 보존합니다.
     */
    fun <T> transaction(connection: Connection, block: JdbcTransaction.() -> T): T {
        val originalAutoCommit = connection.autoCommit
        val database = Database.connect(
            getNewConnection = { nonClosing(connection) },
            databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
        )
        return try {
            transaction(database) { block() }
        } finally {
            runCatching { connection.autoCommit = originalAutoCommit }
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun nonClosing(connection: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "close" && method.parameterCount == 0) {
                null
            } else {
                try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw (failure.targetException ?: failure)
                }
            }
        } as Connection

    /** DataSource가 닫히기 전에 Exposed의 manager 등록을 해제합니다. */
    override fun close() {
        TransactionManager.closeAndUnregister(database)
    }
}
