package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class LikeTest: AbstractExposedTest() {

    companion object: KLogging()

    object T1: Table("T1") {
        val id = integer("charnum")
        val char = varchar("thechar", 255)

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, char)
        }
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

            charRange.forEach { ch ->
                T1.insert {
                    it[id] = ch.code
                    it[char] = ch.toString()
                }
            }

            val specialChars = charRange.filter { ch ->
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE 'A' ESCAPE '+'
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE '%' ESCAPE '+'
                // SELECT COUNT(*) FROM T1 WHERE T1.THECHAR LIKE '_' ESCAPE '+'
                T1.selectAll()
                    .where {
                        T1.char like LikePattern(ch.toString(), escapeChar = escapeChar)
                    }
                    .count().toInt() != 1
            }
            // ['%', '_']
            log.debug { "Special chars: $specialChars" }
            specialChars.toSet() shouldBeEqualTo dialectSpecialChars.keys
        }
    }

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
