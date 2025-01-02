package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.inProperCase
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.statements.StatementType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.ResultSet

class TransactionExecTest: AbstractExposedTest() {

    object ExecTable: Table("exec_table") {
        val id = integer("id").autoIncrement("exec_id_seq")
        val amount = integer("amount")
        override val primaryKey = PrimaryKey(id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exec with single statement query`(dialect: TestDB) {
        withTables(dialect, ExecTable) {
            val amounts = (90..99).toList()
            ExecTable.batchInsert(amounts, shouldReturnGeneratedValues = false) { amount ->
                this[ExecTable.id] = (amount % 10 + 1)
                this[ExecTable.amount] = amount
            }

            val results = exec(
                """SELECT * FROM ${ExecTable.tableName.inProperCase()}""",
                explicitStatementType = StatementType.SELECT
            ) { resultSet: ResultSet ->
                val allAmounts = mutableListOf<Int>()
                while (resultSet.next()) {
                    allAmounts.add(resultSet.getInt("amount"))
                }
                allAmounts
            }
            results.shouldNotBeNull()
            results shouldBeEqualTo amounts
        }
    }
}
