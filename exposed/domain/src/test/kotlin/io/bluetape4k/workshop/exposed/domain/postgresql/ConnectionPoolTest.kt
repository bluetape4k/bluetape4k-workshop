package io.bluetape4k.workshop.exposed.domain.postgresql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

class ConnectionPoolTest: AbstractExposedTest() {

    companion object: KLogging()

    private val hikariDataSourcePG by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = TestDB.POSTGRESQL.connection.invoke()
                username = TestDB.POSTGRESQL.user
                password = TestDB.POSTGRESQL.pass
                // sets the default schema for connections, which opens a database transaction before Exposed does
                schema = "public"
                maximumPoolSize = 10
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_SERIALIZABLE"
                isReadOnly = true
            }
        )
    }

    private lateinit var hikariPG: Database

    @BeforeEach
    fun beforeEach() {
        hikariPG = Database.connect(hikariDataSourcePG)
    }

    @AfterEach
    fun afterEach() {
        TransactionManager.closeAndUnregister(hikariPG)
    }

    @Test
    fun `schema and connections with hikari and postgresql`() {
        Assumptions.assumeTrue { TestDB.POSTGRESQL in TestDB.enabledDialects() }

        // setting default schema directly in hikari config should not throw exception when Exposed creates
        // a new transaction and checks if connection parameters need to be reset
        transaction(db = hikariPG) {
            val schema = exec("SELECT CURRENT_SCHEMA;") {
                it.next()
                it.getString(1)
            }
            schema shouldBeEqualTo "public"
        }
    }

    @Test
    fun `readonly mode with hikari and postgres`() {
        Assumptions.assumeTrue { TestDB.POSTGRESQL in TestDB.enabledDialects() }

        // read only mode should be set directly by hikari config
        transaction(db = hikariPG) {
            getReadOnlyMode().shouldBeTrue()

            // table creation should not fail
            expectException<ExposedSQLException> {
                SchemaUtils.create(TestTable)
            }
        }

        transaction(
            transactionIsolation = Connection.TRANSACTION_SERIALIZABLE,
            readOnly = false,
            db = hikariPG
        ) {
            getReadOnlyMode().shouldBeFalse()

            // table can now be created and dropped
            SchemaUtils.create(TestTable)
            SchemaUtils.drop(TestTable)
        }
    }

    @Test
    fun `suspended readonly mode with hikari and postgres`() = runTest {
        Assumptions.assumeTrue { TestDB.POSTGRESQL in TestDB.enabledDialects() }

        // read only mode should be set directly by hikari config
        newSuspendedTransaction(db = hikariPG) {
            getReadOnlyMode().shouldBeTrue()

            // read-only 이므로 테이블 생성은 실패한다.
            expectException<ExposedSQLException> {
                SchemaUtils.create(TestTable)
            }
        }

        // transaction setting should override hikari config
        newSuspendedTransaction(
            transactionIsolation = Connection.TRANSACTION_SERIALIZABLE,
            readOnly = false,
            db = hikariPG
        ) {
            getReadOnlyMode().shouldBeFalse()

            // read-only 가 아니므로 테이블 생성, 삭제 작업이 가능하다.
            SchemaUtils.create(TestTable)
            SchemaUtils.drop(TestTable)
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS hikari_tester (
     *      id SERIAL PRIMARY KEY
     * )
     * ```
     */
    private val TestTable = object: IntIdTable("HIKARI_TESTER") {}

    private fun Transaction.getReadOnlyMode(): Boolean {
        val mode = exec("SHOW transaction_read_only;") {
            it.next()
            it.getBoolean(1)
        }
        return mode!!
    }
}
