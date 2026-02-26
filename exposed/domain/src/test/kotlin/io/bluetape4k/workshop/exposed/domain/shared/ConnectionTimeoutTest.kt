package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import org.amshove.kluent.fail
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLTransientException

class ConnectionTimeoutTest: AbstractExposedTest() {

    private class ExceptionOnGetConnectionDataSource: DataSourceStub() {
        var connectCount = 0

        override fun getConnection(): Connection {
            connectCount++
            throw GetConnectException()
        }
    }

    private class GetConnectException: SQLTransientException()

    @Test
    fun `connect fail causes repeated connect attempts`() {
        val datasource = ExceptionOnGetConnectionDataSource()
        val db = Database.connect(datasource = datasource)

        try {
            transaction(db = db, transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 42
                exec("SELECT 1;")
                // NO OP
            }
            fail("Should have thrown ${GetConnectException::class.simpleName}")
        } catch (e: ExposedSQLException) {
            e.cause shouldBeInstanceOf GetConnectException::class
            datasource.connectCount shouldBeEqualTo 42
        }
    }

    @Test
    fun `transaction repeatition with defaults`() {
        val datasource = ExceptionOnGetConnectionDataSource()
        val db = Database.connect(
            datasource = datasource,
            databaseConfig = DatabaseConfig {
                defaultMaxAttempts = 10
            }
        )

        try {
            // transaction block should use default DatabaseConfig values when no property is set
            transaction(db = db, transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                exec("SELECT 1;")
            }
            fail("Should have thrown ${GetConnectException::class.simpleName}")
        } catch (e: ExposedSQLException) {
            e.cause shouldBeInstanceOf GetConnectException::class
            datasource.connectCount shouldBeEqualTo 10
        }

        datasource.connectCount = 0

        try {
            // property set in transaction block should override default DatabaseConfig
            transaction(db = db, transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 25
                exec("SELECT 1;")
            }
            fail("Should have thrown ${GetConnectException::class.simpleName}")
        } catch (e: ExposedSQLException) {
            e.cause shouldBeInstanceOf GetConnectException::class
            datasource.connectCount shouldBeEqualTo 25
        }
    }
}
