package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.addLogger
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * SQL Statements 에 Parameter 를 전달하여 실행하는 방식에 대한 테스트 코드입니다.
 *
 * ```kotlin
 * Transaction.exec(
 *      "INSERT INTO tmp (foo) VALUES (?)",
 *      listOf(VarCharColumnType() to "John \"Johny\" Johnson"),
 *      INSERT
 * )
 * ```
 */
class ParameterizationTest: AbstractExposedTest() {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS tmp (
     *      foo VARCHAR(50) NULL
     * )
     * ```
     */
    object TempTable: Table("tmp") {
        val name = varchar("foo", 50).nullable()
    }

    private val supportMultipleStatements = TestDB.ALL_MYSQL + TestDB.POSTGRESQL

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with quotes and get it back`(testDB: TestDB) {
        withTables(testDB, TempTable) {
            exec(
                stmt = "INSERT INTO ${TempTable.tableName} (foo) VALUES (?)",
                args = listOf(VarCharColumnType() to "John \"Johny\" Johnson"),
                StatementType.INSERT
            )

            TempTable.selectAll().single()[TempTable.name] shouldBeEqualTo "John \"Johny\" Johnson"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `single parameters with multiple statements`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportMultipleStatements }

        val db = Database.connect(
            testDB.connection.invoke().plus(urlExtra(testDB)),
            testDB.driver,
            testDB.user,
            testDB.pass,
        )

        transaction(db) {
            try {
                SchemaUtils.create(TempTable)

                val table = TempTable.tableName.inProperCase()
                val column = TempTable.name.name.inProperCase()

                /**
                 * 복합적인 SQL 문을 파라미터를 사용하여 실행합니다.
                 */
                val result = exec(
                    """
                        INSERT INTO $table ($column) VALUES (?);
                        INSERT INTO $table ($column) VALUES (?);
                        INSERT INTO $table ($column) VALUES (?);
                        DELETE FROM $table WHERE $table.$column LIKE ?;
                        SELECT COUNT(*) FROM $table;
                    """.trimIndent(),
                    listOf(
                        VarCharColumnType() to "Anne",
                        VarCharColumnType() to "Anya",
                        VarCharColumnType() to "Anna",
                        VarCharColumnType() to "Ann%"
                    ),
                    StatementType.MULTI
                ) { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
                result shouldBeEqualTo 1

                TempTable.selectAll().single()[TempTable.name] shouldBeEqualTo "Anya"
            } finally {
                SchemaUtils.drop(TempTable)
            }
        }

        TransactionManager.closeAndUnregister(db)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `multiple parameters with multiple statements`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportMultipleStatements }

        val tester = object: Table("tester") {
            val name = varchar("foo", 50)
            val age = integer("age")
            val active = bool("active")
        }

        val db = Database.connect(
            testDB.connection.invoke().plus(urlExtra(testDB)),
            testDB.driver,
            testDB.user,
            testDB.pass,
        )

        transaction(db) {
            try {
                SchemaUtils.create(tester)

                val table = tester.tableName.inProperCase()
                val (name, age, active) = tester.columns.map { it.name.inProperCase() }

                val result = exec(
                    """
                        INSERT INTO $table ($active, $age, $name) VALUES (?, ?, ?);
                        INSERT INTO $table ($active, $age, $name) VALUES (?, ?, ?);
                        UPDATE $table SET $age=? WHERE ($table.$name LIKE ?) AND ($table.$active = ?);
                        SELECT COUNT(*) FROM $table WHERE ($table.$name LIKE ?) AND ($table.$age = ?);
                    """.trimIndent(),
                    args = listOf(
                        BooleanColumnType() to true, IntegerColumnType() to 1, VarCharColumnType() to "Anna",
                        BooleanColumnType() to false, IntegerColumnType() to 1, VarCharColumnType() to "Anya",
                        IntegerColumnType() to 2, VarCharColumnType() to "A%", BooleanColumnType() to true,
                        VarCharColumnType() to "A%", IntegerColumnType() to 2
                    ),
                    explicitStatementType = StatementType.MULTI
                ) { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
                result shouldBeEqualTo 1

                tester.selectAll().count().toInt() shouldBeEqualTo 2

            } finally {
                SchemaUtils.drop(tester)
            }
        }

        TransactionManager.closeAndUnregister(db)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `null parameter with logger`(testDB: TestDB) {
        withTables(testDB, TempTable) {
            // the logger is left in to test that it does not throw IllegalStateException with null parameter arg
            addLogger(StdOutSqlLogger)

            /**
             * ```sql
             * INSERT INTO tmp (foo) VALUES (NULL)
             * ```
             */
            exec(
                stmt = "INSERT INTO ${TempTable.tableName} (${TempTable.name.name}) VALUES (?)",
                args = listOf(VarCharColumnType() to null),
                explicitStatementType = StatementType.INSERT,
            )

            TempTable.selectAll().single()[TempTable.name].shouldBeNull()
        }
    }

    /**
     * MySQL jdbcUrl에 allowMultiQueries=true 를 추가해야 합니다.
     */
    private fun urlExtra(testDB: TestDB): String {
        return when (testDB) {
            in TestDB.ALL_MYSQL -> "&allowMultiQueries=true"
            else -> ""
        }
    }
}
