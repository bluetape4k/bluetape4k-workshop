package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.inProperCase
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.autoIncColumnType
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.ResultSet

class TransactionExecTest: AbstractExposedTest() {

    companion object: KLogging()

    object ExecTable: Table("exec_table") {
        val id = integer("id").autoIncrement("exec_id_seq")
        val amount = integer("amount")

        override val primaryKey = PrimaryKey(id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exec with single statement query`(testDB: TestDB) {
        withTables(testDB, ExecTable) {
            val amounts = (90..99).toList()

            ExecTable.batchInsert(amounts, shouldReturnGeneratedValues = false) { amount ->
                // this[ExecTable.id] = (amount % 10 + 1)  // autoIncrement 라 지정할 필요 없지 않나? - 속도 문제일 뿐
                this[ExecTable.amount] = amount
            }

            val results: MutableList<Int> = exec(
                """SELECT * FROM ${ExecTable.tableName.inProperCase()};""",
                explicitStatementType = StatementType.SELECT
            ) { resultSet: ResultSet ->
                val allAmounts = mutableListOf<Int>()
                while (resultSet.next()) {
                    val id = resultSet.getInt("id")
                    val loadedAmount = resultSet.getInt("amount")
                    log.debug { "Loaded id=$id, amount: $loadedAmount" }
                    allAmounts.add(loadedAmount)
                }
                allAmounts
            }.shouldNotBeNull()

            results shouldBeEqualTo amounts
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exec with multi statement query`(testDB: TestDB) {
        // PGjdbc-NG driver does not allow multiple commands in a single prepared statement.
        // Both SQLite and H2 drivers allow multiple but only return the result of the first statement:
        // SQLite issue tracker: https://github.com/xerial/sqlite-jdbc/issues/277
        // H2 issue tracker: https://github.com/h2database/h2database/issues/3704
        val toExclude = TestDB.ALL_H2 + TestDB.ALL_MYSQL_LIKE + listOf(TestDB.POSTGRESQLNG)

        Assumptions.assumeTrue { testDB !in toExclude }
        withTables(testDB, ExecTable) {
            testInsertAndSelectInSingleExec(testDB)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exec with multi statement query using MySQL`(testDB: TestDB) {
        // Assumptions.assumeTrue { TestDB.ALL_MYSQL.containsAll(TestDB.enabledDialects()) }
        // val testDB = TestDB.enabledDialects().first()

        Assumptions.assumeTrue { testDB == TestDB.MYSQL_V8 }

        val extra = "" //if (testDB in TestDB.ALL_MARIADB) "?" else ""
        val db = Database.connect(
            testDB.connection().plus("$extra&allowMultiQueries=true"),
            testDB.driver,
            testDB.user,
            testDB.pass
        )

        transaction(db) {
            try {
                SchemaUtils.create(ExecTable)
                testInsertAndSelectInSingleExec(testDB)
            } finally {
                SchemaUtils.drop(ExecTable)
            }
        }
    }

    private fun Transaction.testInsertAndSelectInSingleExec(testDB: TestDB) {
        ExecTable.insert {
            it[amount] = 99
        }

        val insertStatement = "INSERT INTO ${ExecTable.tableName.inProperCase()} " +
                "(${ExecTable.amount.name.inProperCase()}, ${ExecTable.id.name.inProperCase()}) " +
                "VALUES (100, ${ExecTable.id.autoIncColumnType?.nextValExpression})"

        val columnAlias = "last_inserted_id"
        val selectLastIdStatement = when (testDB) {
            TestDB.POSTGRESQL -> "SELECT lastval() AS $columnAlias;"
            else              -> "SELECT LAST_INSERT_ID() AS $columnAlias;"
        }

        val insertAndSelectStatements =
            """
            $insertStatement;
            $selectLastIdStatement;
            """.trimIndent()

        val result = exec(
            insertAndSelectStatements,
            explicitStatementType = StatementType.MULTI
        ) { resultSet ->
            resultSet.next()
            resultSet.getInt(columnAlias)
        }
        result.shouldNotBeNull() shouldBeEqualTo 2
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exec with nullable and empty resultSets`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val id = integer("id")
            val title = varchar("title", 32)
        }

        withTables(testDB, tester) {
            tester.insert {
                it[id] = 1
                it[title] = "Exposed"
            }

            val (table, id, title) =
                listOf(tester.tableName, tester.id.name, tester.title.name)

            val stringResult = exec("""SELECT $title FROM $table WHERE $id = 1;""") { rs ->
                rs.next()
                rs.getString(title)
            }
            stringResult shouldBeEqualTo "Exposed"

            // no record exists for id = 999, but result set returns single nullable value due to subquery alias
            val nullColumnResult = exec("""SELECT (SELECT $title FROM $table WHERE $id = 999) AS sub;""") { rs ->
                rs.next()
                rs.getString("sub")
            }
            nullColumnResult.shouldBeNull()

            // no record exists for id = 999, so result set is empty and rs.next() is false
            val nullTransformResult = exec("""SELECT $title FROM $table WHERE $id = 999;""") { rs ->
                if (rs.next()) {
                    rs.getString(title)
                } else {
                    null
                }
            }
            nullTransformResult.shouldBeNull()
        }
    }
}
