package io.bluetape4k.workshop.exposed.domain.h2

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.inProperCase
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transactionManager
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class H2Test: AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `Get H2 Version`() {
        withDb(TestDB.ALL_H2) {
            val dialect = currentDialect
            if (dialect is H2Dialect) {
                val version = exec("SELECT H2VERSION();") {
                    it.next()
                    it.getString(1)
                }
                log.info { "H2 Version: $version" }
                version?.first()?.toString() shouldBeEqualTo "2"
            }
        }
    }

    @Test
    fun `insert in H2`() {
        withTables(TestDB.enabledDialects() - setOf(TestDB.H2, TestDB.H2_MYSQL), Testing) {
            Testing.insert {
                it[id] = 1
                it[string] = "one"
            }
            val row = Testing.selectAll().where { Testing.id.eq(1) }.single()
            row[Testing.string] shouldBeEqualTo "one"
        }
    }

    @Test
    fun `replace as insert in H2`() {
        withTables(TestDB.enabledDialects() - setOf(TestDB.H2_MYSQL), Testing) {
            Testing.replace {
                it[id] = 1
                it[string] = "one"
            }
            val row = Testing.selectAll().where { Testing.id.eq(1) }.single()
            row[Testing.string] shouldBeEqualTo "one"
        }
    }

    @Test
    fun `close and unregister`() {
        withDb(TestDB.H2) { testDB ->
            val originalManager = TransactionManager.manager
            val db = testDB.db.requireNotNull("testDB.db")

            try {
                TransactionManager.registerManager(db, WrappedTransactionManager(db.transactionManager))
                Executors.newSingleThreadExecutor().apply {
                    submit {
                        TransactionManager.closeAndUnregister(db)
                    }.get(1, TimeUnit.SECONDS)
                }.shutdown()
            } finally {
                TransactionManager.registerManager(db, originalManager)
            }
        }
    }

    @Test
    fun `add auto primary key`() {
        val tableName = "Foo"
        val initialTable = object: Table(tableName) {
            val bar = text("bar")
        }
        val t = IntIdTable(tableName)

        withDb(listOf(TestDB.H2, TestDB.H2_MYSQL)) {
            try {
                SchemaUtils.createMissingTablesAndColumns(initialTable)
                t.id.ddl.first() shouldBeEqualTo
                        "ALTER TABLE ${tableName.inProperCase()} ADD ${"id".inProperCase()} ${t.id.columnType.sqlType()}"

                t.id.ddl[1] shouldBeEqualTo
                        "ALTER TABLE ${tableName.inProperCase()} ADD CONSTRAINT pk_$tableName PRIMARY KEY (${"id".inProperCase()})"

                currentDialectTest.tableColumns(t)[t]!!.size shouldBeEqualTo 1
                SchemaUtils.createMissingTablesAndColumns(t)
                currentDialectTest.tableColumns(t)[t]!!.size shouldBeEqualTo 2
            } finally {
                SchemaUtils.drop(t)
            }
        }
    }

    class WrappedTransactionManager(val tm: TransactionManager): TransactionManager by tm

    object Testing: Table("H2_TESTING") {
        val id = integer("id").autoIncrement()
        val string = varchar("string", 128)

        override val primaryKey = PrimaryKey(id)
    }
}
