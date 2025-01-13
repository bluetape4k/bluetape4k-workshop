package io.bluetape4k.workshop.exposed.domain.shared.types

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CharColumnTypeTest: AbstractExposedTest() {

    companion object: KLogging()

    object CharTable: IntIdTable("charTable") {
        val charColumn = char("charColumn")
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `char column read and write`(testDB: TestDB) {
        withTables(testDB, CharTable) {
            val id = CharTable.insertAndGetId {
                it[charColumn] = 'A'
            }

            val result = CharTable.selectAll().where { CharTable.id eq id }.singleOrNull()
            result?.get(CharTable.charColumn) shouldBeEqualTo 'A'
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `char column with collate`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES + TestDB.ALL_MYSQL_LIKE)
        val collateOption = when (testDB) {
            in TestDB.ALL_POSTGRES -> "C"
            else                   -> "utf8mb4_bin"
        }

        /**
         * MySQL:
         * ```sql
         * CREATE TABLE IF NOT EXISTS tester (letter CHAR(1) COLLATE utf8mb4_bin NOT NULL)
         * ```
         * Postgres:
         * ```sql
         * CREATE TABLE IF NOT EXISTS tester (letter CHAR(1) COLLATE "C" NOT NULL)
         * ```
         */
        val tester = object: Table("tester") {
            val letter = char("letter", 1, collate = collateOption)
        }

        // H2 only allows collation for the entire database using SET COLLATION
        // Oracle only allows collation if MAX_STRING_SIZE=EXTENDED, which can only be set in upgrade mode
        // Oracle -> https://docs.oracle.com/en/database/oracle/oracle-database/12.2/refrn/MAX_STRING_SIZE.html#
        withTables(testDB, tester) {
            val letters = listOf("a", "A", "b", "B")
            tester.batchInsert(letters) { ch ->
                this[tester.letter] = ch
            }

            // one of the purposes of collation is to determine ordering rules of stored character data types
            val expected = letters.sortedBy { it.first().code } // [A, B, a, b]
            val actual = tester.selectAll().orderBy(tester.letter).map { it[tester.letter] }

            actual shouldBeEqualTo expected
        }
    }
}
