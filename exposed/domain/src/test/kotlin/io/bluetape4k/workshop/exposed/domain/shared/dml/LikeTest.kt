package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class LikeTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS t1 (
     *      charnum INT PRIMARY KEY,
     *      thechar VARCHAR(255) NOT NULL
     * );
     *
     * CREATE INDEX t1_thechar ON t1 (thechar);
     * ```
     */
    object T1: Table("T1") {
        val id = integer("charnum")
        val char = varchar("thechar", 255).index()

        override val primaryKey = PrimaryKey(id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `detect special chars`(testDB: TestDB) {
        withTables(testDB, T1) {
            // Lets assume there are no special chars outside this range
            val escapeChar = '+'
            val dialectSpecialChars = db.dialect.likePatternSpecialChars
            val charRange = ('A'..'Z').toSet() +
                    dialectSpecialChars.keys +
                    dialectSpecialChars.values.filterNotNull()

            /**
             * ```sql
             * INSERT INTO t1 (charnum, thechar) VALUES (65, 'A');
             * INSERT INTO t1 (charnum, thechar) VALUES (66, 'B');
             * ...
             * INSERT INTO t1 (charnum, thechar) VALUES (90, 'Z');
             * INSERT INTO t1 (charnum, thechar) VALUES (37, '%');
             * INSERT INTO t1 (charnum, thechar) VALUES (95, '_');
             * ```
             */
            T1.batchInsert(charRange) { ch ->
                this[T1.id] = ch.code
                this[T1.char] = ch.toString()
            }

            val specialChars = charRange.filter { ch ->
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE 'A' ESCAPE '+'
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE 'B' ESCAPE '+'
                // ...
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE 'Z' ESCAPE '+'
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE '%' ESCAPE '+'
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE '_' ESCAPE '+'
                T1.selectAll()
                    .where {
                        T1.char like LikePattern(ch.toString(), escapeChar = escapeChar)
                    }
                    .count() != 1L
            }
            // spectialChars = ['%', '_']
            log.debug { "Special chars: $specialChars" }
            specialChars.toSet() shouldBeEqualTo dialectSpecialChars.keys
        }
    }

    /**
     * ```sql
     * INSERT INTO t1 (charnum, thechar) VALUES (1, '%a%')
     * INSERT INTO t1 (charnum, thechar) VALUES (2, '_a')
     * INSERT INTO t1 (charnum, thechar) VALUES (3, '_b')
     * INSERT INTO t1 (charnum, thechar) VALUES (4, '\a')
     * SELECT t1.charnum, t1.thechar FROM t1 WHERE t1.thechar LIKE '\_a' ESCAPE '\'
     * SELECT t1.charnum, t1.thechar FROM t1 WHERE t1.thechar LIKE '\%a\%' ESCAPE '\'
     * SELECT t1.charnum, t1.thechar FROM t1 WHERE t1.thechar LIKE '\\a' ESCAPE '\'
     * SELECT t1.charnum, t1.thechar FROM t1 WHERE t1.thechar LIKE '\_%' ESCAPE '\'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select with like`(testDb: TestDB) {
        withTables(testDb, T1) {
            var i = 1
            T1.insert {
                it[id] = i++
                it[char] = "%a%"
            }
            T1.insert {
                it[id] = i++
                it[char] = "_a"
            }
            T1.insert {
                it[id] = i++
                it[char] = "_b"
            }
            T1.insert {
                it[id] = i++
                it[char] = "\\a"
            }

            // SELECT T1.CHARNUM, T1.THECHAR FROM T1 WHERE T1.THECHAR LIKE '\_a' ESCAPE '\'
            T1.selectAll()
                .where { T1.char like LikePattern.ofLiteral("_a") }
                .firstOrNull()
                ?.get(T1.char) shouldBeEqualTo "_a"

            // SELECT T1.CHARNUM, T1.THECHAR FROM T1 WHERE T1.THECHAR LIKE '\%a\%' ESCAPE '\'
            T1.selectAll()
                .where { T1.char like LikePattern.ofLiteral("%a%") }
                .firstOrNull()
                ?.get(T1.char) shouldBeEqualTo "%a%"

            // SELECT T1.CHARNUM, T1.THECHAR FROM T1 WHERE T1.THECHAR LIKE '\\a' ESCAPE '\'
            T1.selectAll()
                .where { T1.char like LikePattern.ofLiteral("\\a") }
                .firstOrNull()
                ?.get(T1.char) shouldBeEqualTo "\\a"

            // SELECT T1.CHARNUM, T1.THECHAR FROM T1 WHERE T1.THECHAR LIKE '\_%' ESCAPE '\'
            T1.selectAll()
                .where { T1.char like LikePattern.ofLiteral("_") + "%" }
                .map { it[T1.char] } shouldBeEqualTo listOf("_a", "_b")
        }
    }
}
