package io.bluetape4k.workshop.exposed.domain.h2

import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.onetomany.CountryTable
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MultiDatabaseTest {

    private val db1 by lazy {
        Database.connect(
            url = "jdbc:h2:mem:db1;DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
            user = "root",
            password = "",
            databaseConfig = DatabaseConfig {
                defaultIsolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED
            }
        )
    }
    private val db2 by lazy {
        Database.connect(
            url = "jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
            user = "root",
            password = "",
            databaseConfig = DatabaseConfig {
                defaultIsolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED
            }
        )
    }

    private var currentDB: Database? = null

    @BeforeEach
    fun beforeEach() {
        Assumptions.assumeTrue { TestDB.H2 in TestDB.enabledDialects() }
        if (TransactionManager.isInitialized()) {
            currentDB = TransactionManager.currentOrNull()?.db
        }
    }

    @AfterEach
    fun afterEach() {
        TransactionManager.resetCurrent(currentDB?.transactionManager)
    }

    @Test
    fun `transaction with database`() {
        transaction(db1) {
            CountryTable.exists().shouldBeFalse()
            SchemaUtils.create(CountryTable)
            CountryTable.exists().shouldBeTrue()
            SchemaUtils.drop(CountryTable)
        }

        transaction(db2) {
            CountryTable.exists().shouldBeFalse()
            SchemaUtils.create(CountryTable)
            CountryTable.exists().shouldBeTrue()
            SchemaUtils.drop(CountryTable)
        }
    }

    @Test
    fun `simple insert in different databases`() {
        transaction(db1) {
            SchemaUtils.create(CountryTable)
            CountryTable.selectAll().empty().shouldBeTrue()
            CountryTable.insert {
                it[CountryTable.name] = "country1"
            }
        }

        transaction(db2) {
            SchemaUtils.create(CountryTable)
            CountryTable.selectAll().empty().shouldBeTrue()
            CountryTable.insert {
                it[CountryTable.name] = "country1"
            }
        }

        transaction(db1) {
            CountryTable.selectAll().count() shouldBeEqualTo 1L
            CountryTable.selectAll().single()[CountryTable.name] shouldBeEqualTo "country1"
            SchemaUtils.drop(CountryTable)
        }

        transaction(db2) {
            CountryTable.selectAll().count() shouldBeEqualTo 1L
            CountryTable.selectAll().single()[CountryTable.name] shouldBeEqualTo "country1"
            SchemaUtils.drop(CountryTable)
        }
    }
}
