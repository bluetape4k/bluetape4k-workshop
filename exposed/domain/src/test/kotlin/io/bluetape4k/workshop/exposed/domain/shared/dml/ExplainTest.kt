package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.MYSQL_V5
import io.bluetape4k.workshop.exposed.TestDB.MYSQL_V8
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteIgnoreWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.explain
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.intParam
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.union
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ExplainTest: AbstractExposedTest() {

    companion object: KLogging()

    private object Countries: IntIdTable("countries") {
        val code = varchar("country_code", 8)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `explain with statements not executed`(testDb: TestDB) {
        withTables(testDb, Countries) {
            val originalCode = "ABC"

            // any statements with explain should not be executed
            // EXPLAIN INSERT INTO COUNTRIES (COUNTRY_CODE) VALUES ('ABC')
            explain { Countries.insert { it[code] = originalCode } }.toList()
            Countries.selectAll().empty().shouldBeTrue()

            Countries.insert { it[code] = originalCode }
            Countries.selectAll().count().toInt() shouldBeEqualTo 1

            // EXPLAIN UPDATE COUNTRIES SET COUNTRY_CODE = 'DEF'
            explain { Countries.update { it[code] = "DEF" } }.toList()
            Countries.selectAll().single()[Countries.code] shouldBeEqualTo originalCode

            // EXPLAIN DELETE FROM COUNTRIES
            explain { Countries.deleteAll() }.toList()
            Countries.selectAll().count().toInt() shouldBeEqualTo 1

            Countries.deleteAll()
            Countries.selectAll().empty().shouldBeTrue()
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `explain with all valid statements not executed`(testDb: TestDB) {
        var explainCount = 0
        val cityName = "City A"

        fun Transaction.explainAndIncrement(body: Transaction.() -> Any?) =
            explain(body = body).also {
                it.toList() // as with select queries, explain is only executed when iterated over
                explainCount++
            }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val testDialect = currentDialectTest
            debug = true
            statementCount = 0

            // select statements
            explainAndIncrement {
                cities.select(cities.id).where { cities.name like "A%" }
            }
            explainAndIncrement {
                (users innerJoin cities)
                    .select(users.name, cities.name)
                    .where { (users.id.eq("andrey") or users.name.eq("sergey")) and users.cityId.eq(cities.id) }
            }
            explainAndIncrement {
                val query1 = users.selectAll().where { users.id eq "andrey" }
                val query2 = users.selectAll().where { users.id eq "sergey" }
                query1.union(query2).limit(1)
            }
            // insert statements
            explainAndIncrement { cities.insert { it[name] = cityName } }
            val subquery = userData.select(userData.userId, userData.comment, intParam(42))
            explainAndIncrement { userData.insert(subquery) }
            // insert or... statements
            if (testDialect !is H2Dialect) {
                explainAndIncrement { cities.insertIgnore { it[name] = cityName } }
                explainAndIncrement { userData.insertIgnore(subquery) }
            }
            if (testDialect is MysqlDialect || testDialect is SQLiteDialect) {
                explainAndIncrement { cities.replace { it[name] = cityName } }
            }
            explainAndIncrement {
                cities.upsert {
                    it[id] = 1
                    it[name] = cityName
                }
            }
            // update statements
            explainAndIncrement { cities.update { it[name] = cityName } }
            if (testDialect !is SQLiteDialect) {
                explainAndIncrement {
                    val join = users.innerJoin(userData)
                    join.update { it[userData.value] = 123 }
                }
            }
            // delete statements
            explainAndIncrement { cities.deleteWhere { cities.id eq 1 } }
            if (testDialect is MysqlDialect) {
                explainAndIncrement { cities.deleteIgnoreWhere { cities.id eq 1 } }
            }
            explainAndIncrement { cities.deleteAll() }

            statementCount shouldBeEqualTo explainCount
            statementStats.keys.all { it.startsWith("EXPLAIN ") }.shouldBeTrue()

            debug = false
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `explain with alalyze`(testDb: TestDB) {
        withTables(testDb, Countries) {
            val originalCode = "ABC"

            // MySQL only allows ANALYZE with SELECT queries
            if (testDb !in TestDB.ALL_MYSQL) {
                // analyze means all wrapped statements should also be executed
                // EXPLAIN ANALYZE INSERT INTO COUNTRIES (COUNTRY_CODE) VALUES ('ABC')
                explain(analyze = true) { Countries.insert { it[code] = originalCode } }.toList()
                Countries.selectAll().count().toInt() shouldBeEqualTo 1

                // EXPLAIN ANALYZE UPDATE COUNTRIES SET COUNTRY_CODE='DEF'
                explain(analyze = true) { Countries.update { it[code] = "DEF" } }.toList()
                Countries.selectAll().single()[Countries.code] shouldBeEqualTo "DEF"

                // EXPLAIN ANALYZE DELETE FROM COUNTRIES
                explain(analyze = true) { Countries.deleteAll() }.toList()
                Countries.selectAll().empty().shouldBeTrue()
            }

            // In MySql prior 8 the EXPLAIN command should be used without ANALYZE modifier
            val analyze = testDb != MYSQL_V5
            // EXPLAIN ANALYZE SELECT COUNTRIES.ID, COUNTRIES.COUNTRY_CODE FROM COUNTRIES
            explain(analyze = analyze) { Countries.selectAll() }.toList()
        }
    }

    /**
     * MYSQL V8
     * ```sql
     * EXPLAIN FORMAT=JSON SELECT countries.id FROM countries WHERE countries.country_code LIKE 'A%'
     *
     * POSTGRESQL
     * ```
     * ```sql
     * EXPLAIN (FORMAT JSON) SELECT countries.id FROM countries WHERE countries.country_code LIKE 'A%'
     * ```
     *
     * MYSQL V8 with ANALYZE
     * ```sql
     * EXPLAIN ANALYZE FORMAT=TREE SELECT countries.id FROM countries WHERE countries.country_code LIKE 'A%'
     * ```
     *
     * POSTGRESQL with ANALYZE
     * ```sql
     * EXPLAIN (ANALYZE TRUE, FORMAT JSON) SELECT countries.id FROM countries WHERE countries.country_code LIKE 'A%'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `explain with options`(testDb: TestDB) {
        val optionsAvailableDb = TestDB.ALL_POSTGRES + TestDB.ALL_MYSQL

        Assumptions.assumeTrue { testDb in optionsAvailableDb }

        withTables(testDb, Countries) {
            val formatOption = when (testDb) {
                in TestDB.ALL_MYSQL_LIKE -> "FORMAT=JSON"
                in TestDB.ALL_POSTGRES   -> "FORMAT JSON"
                else                     -> throw UnsupportedOperationException("Format option not provided for this dialect")
            }

            val query = Countries.select(Countries.id).where { Countries.code like "A%" }
            val result = explain(options = formatOption) { query }.single()
            val jsonString = result.toString().substringAfter("=")
            log.debug { "JSON: $jsonString" }
            when (testDb) {
                in TestDB.ALL_MYSQL_LIKE -> jsonString.startsWith('{').shouldBeTrue()
                else                     -> jsonString.startsWith('[').shouldBeTrue()
            }

            // test multiple options only
            if (testDb in TestDB.ALL_POSTGRES) {
                // EXPLAIN (VERBOSE TRUE, COSTS FALSE) SELECT countries.id FROM countries WHERE countries.country_code LIKE 'A%'
                explain(options = "VERBOSE TRUE, COSTS FALSE") { query }.toList()
            }

            // test analyze + options
            val analyze = testDb != MYSQL_V5
            val combinedOption = if (testDb == MYSQL_V8) "FORMAT=TREE" else formatOption
            explain(analyze, combinedOption) { query }.toList()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `explain with invalid statements`(testDb: TestDB) {
        withTables(testDb, Countries) {
            expectException<IllegalStateException> {
                // EXPLAIN INSERT INTO COUNTRIES (COUNTRY_CODE) VALUES ('ABC')
                explain { Countries.insertAndGetId { it[code] = "ABC" } }
            }
            expectException<IllegalStateException> {
                explain {
                    Countries.selectAll()
                    "Last line in lambda should be expected return value - statement"
                }
            }

            debug = true
            statementCount = 0

            // only the last statement will be executed with explain
            explain {
                // EXPLAIN DELETE FROM COUNTRIES
                Countries.deleteAll()
                // EXPLAIN SELECT * FROM COUNTRIES
                Countries.selectAll()
            }.toList()

            statementCount shouldBeEqualTo 1
            val executed = statementStats.keys.single()

            executed.startsWith("EXPLAIN ").shouldBeTrue()
            ("SELECT " in executed).shouldBeTrue()
            ("DELETE " !in executed).shouldBeTrue()

            debug = false
        }
    }
}
