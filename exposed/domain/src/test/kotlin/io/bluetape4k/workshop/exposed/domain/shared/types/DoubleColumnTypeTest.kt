package io.bluetape4k.workshop.exposed.domain.shared.types

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DoubleColumnTypeTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS double_table (
     *      id SERIAL PRIMARY KEY,
     *      amount DOUBLE PRECISION NOT NULL
     * )
     * ```
     */
    object TestTable: IntIdTable("double_table") {
        val amount = double("amount")
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select from double column`(testDB: TestDB) {
        withTables(testDB, TestTable) {
            val id = TestTable.insertAndGetId {
                it[amount] = 9.23
            }

            TestTable.selectAll()
                .where { TestTable.id eq id }
                .single()[TestTable.amount] shouldBeEqualTo 9.23
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select from real column`(testDB: TestDB) {
        withDb(testDB) {
            val originalColumnDDL = TestTable.amount.descriptionDdl()
            val realColumnDDL = originalColumnDDL.replace(" DOUBLE PRECISION ", " REAL ")

            /**
             * create table with double() column that uses SQL type REAL
             *
             * Postgres:
             * ```sql
             * CREATE TABLE IF NOT EXISTS double_table (id SERIAL PRIMARY KEY, amount REAL NOT NULL)
             * ```
             */
            TestTable.ddl
                .map { it.replace(originalColumnDDL, realColumnDDL) }
                .forEach { exec(it) }

            val id = TestTable.insertAndGetId {
                it[amount] = 9.23
            }

            TestTable.selectAll()
                .where { TestTable.id eq id }
                .single()[TestTable.amount] shouldBeEqualTo 9.23

            SchemaUtils.drop(TestTable)
        }
    }
}
