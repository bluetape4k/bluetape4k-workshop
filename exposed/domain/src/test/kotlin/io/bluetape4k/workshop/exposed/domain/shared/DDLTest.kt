package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.ExperimentalKeywordApi
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

class DDLTest: AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `table exists`() {
        val testTable = object: Table() {
            val id = integer("id")
            val name = varchar("name", length = 42)

            override val primaryKey = PrimaryKey(id)
        }

        withTables {
            testTable.exists().shouldBeFalse()
        }

        withTables(testTable) {
            testTable.exists().shouldBeTrue()
        }
    }

    private val keywordFlagDB by lazy {
        Database.connect(
            "jdbc:h2:mem:keywordFlagDB;DB_CLOSE_DELAY=-1;",
            "org.h2.Driver",
            "root",
            "",
            databaseConfig = DatabaseConfig {
                @OptIn(ExperimentalKeywordApi::class)
                preserveKeywordCasing = false
            }
        )
    }

    @Test
    fun `keywoard identifiers with opt out`() {
        Assumptions.assumeTrue { TestDB.H2 in TestDB.enabledDialects() }

        val keywords = listOf("Integer", "name")
        val tester = object: Table(keywords[0]) {
            val name = varchar(keywords[1], length = 32)
        }

        transaction(keywordFlagDB) {
            log.debug { "DB Config preserveKeywordCasing=false" }
            db.config.preserveKeywordCasing.shouldBeFalse()

            tester.exists().shouldBeFalse()

            SchemaUtils.create(tester)
            tester.exists().shouldBeTrue()

            val (tableName, columnName) = keywords.map { "\"${it.uppercase()}\"" }

            val expectedCreate = "CREATE TABLE ${addIfNotExistsIfSupported()}$tableName (" +
                    "$columnName ${tester.name.columnType.sqlType()} NOT NULL)"
            tester.ddl.single() shouldBeEqualTo expectedCreate

            // check that insert and select statement identifiers also match in DB without throwing SQLException
            tester.insert { it[name] = "A" }

            val expectedSelect = "SELECT $tableName.$columnName FROM $tableName"
            tester.selectAll().also {
                it.prepareSQL(this, prepared = false) shouldBeEqualTo expectedSelect
            }

            // check that identifiers match with returned jdbc metadata
            val statements = SchemaUtils.statementsRequiredToActualizeScheme(tester, withLogs = false)
            statements.isEmpty().shouldBeTrue()

            SchemaUtils.drop(tester)
        }

        TransactionManager.closeAndUnregister(keywordFlagDB)
    }
}
