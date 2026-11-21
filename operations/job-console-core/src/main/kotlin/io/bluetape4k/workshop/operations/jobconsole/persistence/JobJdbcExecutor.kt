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
    /**
     * 운영 작업 콘솔 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val database = Database.connect(
        datasource = dataSource,
        databaseConfig = DatabaseConfig {
            defaultMaxAttempts = 1
        },
    )

    /**
     * transaction은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> transaction(block: (Connection) -> T): T = transaction(database) {
        block(connection.connection as Connection)
    }

    /**
     * exposedTransaction은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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

    /**
     * nonClosing은 운영 작업 콘솔 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
    /** DataSource가 닫히기 전에 Exposed의 manager 등록을 해제합니다. */
    override fun close() {
        TransactionManager.closeAndUnregister(database)
    }
}
