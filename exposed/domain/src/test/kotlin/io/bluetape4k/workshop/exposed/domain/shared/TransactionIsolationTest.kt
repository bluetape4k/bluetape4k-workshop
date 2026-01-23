package io.bluetape4k.workshop.exposed.domain.shared

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.JdbcTransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection

class TransactionIsolationTest: AbstractExposedTest() {

    companion object: KLogging()

    private val transactionIsolationSupportDB = TestDB.ALL_MARIADB + TestDB.MYSQL_V5 + TestDB.POSTGRESQL

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `what transaction isolation was applied`(testDB: TestDB) {
        withDb(testDB) {
            inTopLevelTransaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                this.connection.transactionIsolation shouldBeEqualTo Connection.TRANSACTION_SERIALIZABLE
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `transaction isolation with HikariDataSource`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in transactionIsolationSupportDB }

        val db = Database.connect(
            HikariDataSource(setupHikariConfig(testDB, "TRANSACTION_REPEATABLE_READ"))
        )
        val manager: JdbcTransactionManager = TransactionManager.managerFor(db)

        transaction(db) {
            // transaction manager should use database default since no level is provided other than hikari
            manager.defaultIsolationLevel shouldBeEqualTo Database.getDefaultIsolationLevel(db)

            // database level should be set by hikari dataSource
            assertTransactionIsolationLevel(testDB, Connection.TRANSACTION_REPEATABLE_READ)

            // after first connection, transaction manager should use hikari level by default
            manager.defaultIsolationLevel shouldBeEqualTo Connection.TRANSACTION_REPEATABLE_READ
        }

        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = db) {
            manager.defaultIsolationLevel shouldBeEqualTo Connection.TRANSACTION_REPEATABLE_READ

            // database level should be set by transaction-specific setting
            assertTransactionIsolationLevel(testDB, Connection.TRANSACTION_READ_COMMITTED)
        }

        transaction(db) {
            manager.defaultIsolationLevel shouldBeEqualTo Connection.TRANSACTION_REPEATABLE_READ

            // database level should be set by hikari dataSource
            assertTransactionIsolationLevel(testDB, Connection.TRANSACTION_REPEATABLE_READ)
        }

        TransactionManager.closeAndUnregister(db)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `transaction isolation with Hikari and Database Config`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in transactionIsolationSupportDB }

        val db = Database.connect(
            HikariDataSource(setupHikariConfig(testDB, "TRANSACTION_REPEATABLE_READ")),
            databaseConfig = DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED }
        )

        val manager: JdbcTransactionManager = TransactionManager.managerFor(db)

        transaction(db) {
            // transaction manager should default to use DatabaseConfig level
            manager.defaultIsolationLevel shouldBeEqualTo Connection.TRANSACTION_READ_COMMITTED

            // database level should be set by DatabaseConfig
            assertTransactionIsolationLevel(testDB, Connection.TRANSACTION_READ_COMMITTED)
            // after first connection, transaction manager should retain DatabaseConfig level
            manager.defaultIsolationLevel shouldBeEqualTo Connection.TRANSACTION_READ_COMMITTED
        }

        transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, db = db) {
            manager?.defaultIsolationLevel shouldBeEqualTo Connection.TRANSACTION_READ_COMMITTED

            // database level should be set by transaction-specific setting
            assertTransactionIsolationLevel(testDB, Connection.TRANSACTION_REPEATABLE_READ)
        }

        transaction(db) {
            manager.defaultIsolationLevel shouldBeEqualTo Connection.TRANSACTION_READ_COMMITTED

            // database level should be set by DatabaseConfig
            assertTransactionIsolationLevel(testDB, Connection.TRANSACTION_READ_COMMITTED)
        }

        TransactionManager.closeAndUnregister(db)
    }


    private fun setupHikariConfig(testDB: TestDB, isolation: String): HikariConfig {
        return HikariConfig().apply {
            jdbcUrl = testDB.connection.invoke()
            driverClassName = testDB.driver
            username = testDB.user
            password = testDB.pass
            maximumPoolSize = 6
            isAutoCommit = false
            transactionIsolation = isolation
            validate()
        }
    }

    private fun JdbcTransaction.assertTransactionIsolationLevel(testDB: TestDB, expected: Int) {
        val (sql, repeatable, committed) = when (testDB) {
            TestDB.POSTGRESQL -> Triple("SHOW TRANSACTION ISOLATION LEVEL", "repeatable read", "read committed")
            in TestDB.ALL_MYSQL_MARIADB -> Triple("SELECT @@tx_isolation", "REPEATABLE-READ", "READ-COMMITTED")
            else -> throw UnsupportedOperationException("Unsupported testDB: $testDB")
        }

        val expectedLevel = when (expected) {
            Connection.TRANSACTION_READ_COMMITTED -> committed
            Connection.TRANSACTION_REPEATABLE_READ -> repeatable
            else -> throw UnsupportedOperationException("Unsupported transaction isolation level: $expected")
        }

        val actual = exec("$sql;") { resultSet ->
            resultSet.next()
            resultSet.getString(1)
        }
        actual.shouldNotBeNull() shouldBeEqualTo expectedLevel
    }
}
