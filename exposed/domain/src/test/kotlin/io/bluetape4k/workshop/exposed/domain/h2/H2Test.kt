package io.bluetape4k.workshop.exposed.domain.h2

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectMetadataTest
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.avg
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.transactions.CoreTransactionManager
import org.jetbrains.exposed.v1.core.transactions.TransactionManagerApi
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.replace
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@Suppress("DEPRECATION")
class H2Test: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Get H2 Version`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withDb(testDB) {
            if (currentDialect is H2Dialect) {
                val version = exec("SELECT H2VERSION();") {
                    it.next()
                    it.getString(1)
                }
                log.info { "H2 Version: $version" }
                version?.first()?.toString() shouldBeEqualTo "2"
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert in H2`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 || testDB == TestDB.H2_MYSQL }

        withTables(testDB, Testing) {
            Testing.insert {
                it[id] = 1
                it[string] = "one"
            }
            val row = Testing.selectAll().where { Testing.id eq 1 }.single()
            row[Testing.string] shouldBeEqualTo "one"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `replace as insert in H2`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2_MYSQL }

        withTables(testDB, Testing) {
            Testing.replace {
                it[id] = 1
                it[string] = "one"
            }
            val row = Testing.selectAll().where { Testing.id eq 1 }.single()
            row[Testing.string] shouldBeEqualTo "one"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `close and unregister`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 }

        withDb(testDB) { testDB ->
            val originalManager = TransactionManager.manager
            val db = testDB.db.requireNotNull("testDB.db")

            try {
                @OptIn(InternalApi::class)
                CoreTransactionManager.registerDatabaseManager(db, WrappedTransactionManager(db.transactionManager))
                Executors.newSingleThreadExecutor().apply {
                    submit {
                        TransactionManager.closeAndUnregister(db)
                    }.get(1, SECONDS)
                }.shutdown()
            } finally {
                TransactionManager.registerManager(db, originalManager)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `add auto primary key`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 || testDB == TestDB.H2_MYSQL }

        val tableName = "Foo"
        val initialTable = object: Table(tableName) {
            val bar = text("bar")
        }
        val t = IntIdTable(tableName)

        withDb(testDB) {
            try {
                SchemaUtils.createMissingTablesAndColumns(initialTable)
                t.id.ddl.first() shouldBeEqualTo
                        "ALTER TABLE ${tableName.inProperCase()} ADD ${"id".inProperCase()} ${t.id.columnType.sqlType()}"

                t.id.ddl[1] shouldBeEqualTo
                        "ALTER TABLE ${tableName.inProperCase()} ADD CONSTRAINT pk_$tableName PRIMARY KEY (${"id".inProperCase()})"


                currentDialectMetadataTest.tableColumns(t)[t]!!.size shouldBeEqualTo 1
                SchemaUtils.createMissingTablesAndColumns(t)
                currentDialectMetadataTest.tableColumns(t)[t]!!.size shouldBeEqualTo 2
            } finally {
                SchemaUtils.drop(t)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testH2V1WithBigDecimalFunctionThatReturnsShort(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }
        val testTable = object: Table("test_table") {
            val number = short("number")
        }

        withTables(testDB, testTable) {
            testTable.batchInsert(listOf<Short>(2, 4, 6, 8, 10)) { n ->
                this[testTable.number] = n
            }

            val average = testTable.number.avg()
            val result = testTable.select(average).single()[average]
            result shouldBeEqualTo "6.00".toBigDecimal()
        }
    }

    class WrappedTransactionManager(val tm: TransactionManagerApi): TransactionManagerApi by tm

    object Testing: Table("H2_TESTING") {
        val id = integer("id").autoIncrement()
        val string = varchar("string", 128)

        override val primaryKey = PrimaryKey(id)
    }
}
