package io.bluetape4k.workshop.exposed.domain.shared

import com.impossibl.postgres.jdbc.PGSQLSimpleException
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.POSTGRESQL
import io.bluetape4k.workshop.exposed.TestDB.POSTGRESQLNG
import io.bluetape4k.workshop.exposed.withDb
import org.amshove.kluent.shouldBeInstanceOf
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.postgresql.util.PSQLException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import kotlin.test.fail

class QueryTimeoutTest: AbstractExposedTest() {

    private fun generateTimeoutStatement(db: TestDB, timeout: Int): String {
        return when (db) {
            in TestDB.ALL_MYSQL    -> "SELECT SLEEP($timeout) = 0;"
            in TestDB.ALL_POSTGRES -> "SELECT pg_sleep($timeout);"
            else                   -> throw NotImplementedError()
        }
    }

    // MySql V5 is excluded now due to error: "java.lang.NoClassDefFoundError: com/mysql/cj/jdbc/exceptions/MySQLTimeoutException"
    // Looks like it tries to load class from the V8 version of driver.
    // Probably it happens because of driver mapping configuration in org.jetbrains.exposed.sql.Database::driverMapping
    // that expects that all the versions of the Driver have the same package.
    private val timeoutTestDBList = TestDB.ALL_POSTGRES + TestDB.MYSQL_V8

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `timeout statements`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in timeoutTestDBList }

        withDb(testDB) {
            this.queryTimeout = 3
            try {
                TransactionManager.current().exec(
                    generateTimeoutStatement(it, 5)
                )
                fail("Should have thrown a timeout or cancelled statement exception")
            } catch (cause: ExposedSQLException) {
                when (testDB) {
                    // PostgreSQL throws a regular PgSQLException with a cancelled statement message
                    POSTGRESQL -> cause.cause shouldBeInstanceOf PSQLException::class
                    // PostgreSQLNG throws a regular PGSQLSimpleException with a cancelled statement message
                    POSTGRESQLNG -> cause.cause shouldBeInstanceOf PGSQLSimpleException::class
                    else                -> cause.cause shouldBeInstanceOf SQLTimeoutException::class
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `no timeout with timeout statement`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in timeoutTestDBList }

        withDb(testDB) {
            this.queryTimeout = 3
            TransactionManager.current().exec(
                generateTimeoutStatement(it, 1)
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `timeout zero with timeout statement`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in timeoutTestDBList }

        withDb(testDB) {
            // 0 means no timeout
            this.queryTimeout = 0
            TransactionManager.current().exec(
                generateTimeoutStatement(it, 1)
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `timeout minus with timeout statement`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in timeoutTestDBList }

        withDb(testDB) {
            this.queryTimeout = -1
            try {
                TransactionManager.current().exec(
                    generateTimeoutStatement(it, 1)
                )
                fail("Should have thrown a timeout or cancelled statement exception")
            } catch (cause: ExposedSQLException) {
                when (testDB) {
                    // PostgreSQL throws a regular PSQLException with a minus timeout value
                    POSTGRESQL -> cause.cause shouldBeInstanceOf PSQLException::class
                    // MySQL, POSTGRESQLNG throws a regular SQLException with a minus timeout value
                    in (TestDB.ALL_MYSQL + POSTGRESQLNG) -> cause.cause shouldBeInstanceOf SQLException::class
                    // MariaDB throws a regular SQLSyntaxErrorException with a minus timeout value
                    // in TestDB.ALL_MARIADB -> assertTrue(cause.cause is SQLSyntaxErrorException)
                    // SqlServer throws a regular SQLServerException with a minus timeout value
                    // TestDB.SQLSERVER -> assertTrue(cause.cause is SQLServerException)
                    else                                        -> throw NotImplementedError()
                }
            }
        }
    }

}
