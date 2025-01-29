package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.toBigDecimal
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.sql.GroupConcat
import org.jetbrains.exposed.sql.Max
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.avg
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.groupConcat
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MariaDBDialect
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.OracleDialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLNGDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.jetbrains.exposed.sql.vendors.VendorDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.jetbrains.exposed.sql.vendors.h2Mode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class GroupByTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * H2
     * ```sql
     * SELECT CITIES."name",
     *        COUNT(USERS.ID),
     *        COUNT(USERS.ID) c
     *   FROM CITIES INNER JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES."name"
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `groupBy example 01`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            val cAlias = users.id.count().alias("c")

            val rows = (cities innerJoin users)
                .select(cities.name, users.id.count(), cAlias)
                .groupBy(cities.name)
                .toList()

            rows.forEach {
                val cityName = it[cities.name]
                val userCount = it[users.id.count()].toInt()
                val userCountAlias = it[cAlias].toInt()
                when (cityName) {
                    "Munich" -> userCount shouldBeEqualTo 2
                    "Prague" -> userCount shouldBeEqualTo 0
                    "St. Petersburg" -> userCount shouldBeEqualTo 1
                    else -> error("Unknown city $cityName")
                }
                userCountAlias shouldBeEqualTo userCount
            }
        }
    }

    /**
     * H2
     * ```sql
     * SELECT CITIES."name",
     *        COUNT(USERS.ID)
     *   FROM CITIES INNER JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES."name"
     * HAVING COUNT(USERS.ID) = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `groupBy example 02`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            val rows = (cities innerJoin users)
                .select(cities.name, users.id.count())
                .groupBy(cities.name)
                .having { users.id.count() eq 1 }
                .toList()

            rows shouldHaveSize 1
            rows[0][cities.name] shouldBeEqualTo "St. Petersburg"
            rows[0][users.id.count()] shouldBeEqualTo 1L
        }
    }

    /**
     * H2
     * ```sql
     * SELECT CITIES."name",
     *        COUNT(USERS.ID),
     *        MAX(CITIES.CITY_ID)
     *   FROM CITIES INNER JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES."name"
     * HAVING COUNT(USERS.ID) = MAX(CITIES.CITY_ID)
     *  ORDER BY CITIES."name" ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `groupBy example 03`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            val maxExpr: Max<Int, Int> = cities.id.max()

            val rows: List<ResultRow> = (cities innerJoin users)
                .select(cities.name, users.id.count(), maxExpr)
                .groupBy(cities.name)
                .having { users.id.count().eq<Number, Long, Int>(maxExpr) }
                // .having { users.id.count() eq maxExpr }
                .orderBy(cities.name)
                .toList()

            rows.forEach { row ->
                log.debug { "city name=${row[cities.name]}, maxExpr=${row[maxExpr]}" }
            }

            rows shouldHaveSize 2

            rows[0].let {
                it[cities.name] shouldBeEqualTo "Munich"
                it[users.id.count()] shouldBeEqualTo 2L
                it[maxExpr] shouldBeEqualTo 2
            }
            rows[1].let {
                it[cities.name] shouldBeEqualTo "St. Petersburg"
                it[users.id.count()] shouldBeEqualTo 1L
                it[maxExpr] shouldBeEqualTo 1
            }
        }
    }

    /**
     * H2
     * ```sql
     * SELECT CITIES."name",
     *        COUNT(USERS.ID),
     *        MAX(CITIES.CITY_ID)
     *   FROM CITIES INNER JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES."name"
     * HAVING COUNT(USERS.ID) <= 42
     *  ORDER BY CITIES."name" ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `groupBy example 04`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->

            val rows = (cities innerJoin users)
                .select(cities.name, users.id.count(), cities.id.max())
                .groupBy(cities.name)
                .having { users.id.count() lessEq 42L }
                .orderBy(cities.name)
                .toList()

            rows shouldHaveSize 2

            rows[0].let {
                it[cities.name] shouldBeEqualTo "Munich"
                it[users.id.count()] shouldBeEqualTo 2L
            }
            rows[1].let {
                it[cities.name] shouldBeEqualTo "St. Petersburg"
                it[users.id.count()] shouldBeEqualTo 1L
            }
        }
    }

    /**
     * H2
     * ```sql
     * SELECT MAX(CITIES.CITY_ID) FROM CITIES
     * ```
     *
     * ```sql
     * SELECT MAX(CITIES.CITY_ID) FROM CITIES
     *  WHERE CITIES.CITY_ID IS NULL
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `groupBy example 06`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            val maxNullableId = cities.id.max()

            cities.select(maxNullableId)
                .map { it[maxNullableId] }
                .let { result ->
                    result shouldHaveSize 1
                    result.single().shouldNotBeNull() shouldBeEqualTo 3
                }

            cities.select(maxNullableId)
                .where { cities.id.isNull() }
                .map { it[maxNullableId] }
                .let { result ->
                    result shouldHaveSize 1
                    result.single().shouldBeNull()
                }
        }
    }

    /**
     * H2
     * ```sql
     * SELECT AVG(CITIES.CITY_ID)
     *   FROM CITIES
     * ```
     *
     * ```sql
     * SELECT AVG(CITIES.CITY_ID)
     *   FROM CITIES
     *  WHERE CITIES.CITY_ID IS NULL
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `groupBy example 07`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            val avgIdExpr = cities.id.avg()
            val avgId = cities.select(cities.id).map { it[cities.id] }.average().toBigDecimal().setScale(2)

            cities.select(avgIdExpr)
                .map { it[avgIdExpr] }
                .let { result ->
                    result shouldHaveSize 1
                    result.single()?.toBigDecimal() shouldBeEqualTo avgId
                }

            cities.select(avgIdExpr)
                .where { cities.id.isNull() }
                .map { it[avgIdExpr] }
                .let { result ->
                    result shouldHaveSize 1
                    result.single().shouldBeNull()
                }
        }
    }

    /**
     * H2
     * ```sql
     * SELECT CITIES."name",
     *        GROUP_CONCAT(USERS."name")
     *   FROM CITIES LEFT JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES.CITY_ID, CITIES."name"
     * ```
     * ```sql
     * SELECT CITIES."name",
     *        GROUP_CONCAT(USERS."name" SEPARATOR ', ')
     *   FROM CITIES LEFT JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES.CITY_ID, CITIES."name"
     * ```
     * ```sql
     * SELECT CITIES."name",
     *        GROUP_CONCAT(DISTINCT USERS."name" SEPARATOR ' | ')
     *   FROM CITIES LEFT JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES.CITY_ID, CITIES."name"
     * ```
     * ```sql
     * SELECT CITIES."name",
     *        GROUP_CONCAT(USERS."name" ORDER BY USERS."name" ASC SEPARATOR ' | ')
     *   FROM CITIES LEFT JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES.CITY_ID, CITIES."name"
     * ```
     * ```sql
     * SELECT CITIES."name",
     *        GROUP_CONCAT(USERS."name" ORDER BY USERS."name" DESC SEPARATOR ' | ')
     *   FROM CITIES LEFT JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY CITIES.CITY_ID, CITIES."name"
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `group concat`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            fun <T: String?> GroupConcat<T>.checkExcept(
                vararg dialects: VendorDialect.DialectNameProvider,
                assert: (Map<String, String?>) -> Unit,
            ) {
                val groupConcat = this
                try {
                    val result = cities.leftJoin(users)
                        .select(cities.name, groupConcat)
                        .groupBy(cities.id, cities.name)
                        .associate { it[cities.name] to it[groupConcat] }
                    assert(result)
                } catch (e: UnsupportedByDialectException) {
                    log.warn(e) { "Unsupported by dialect: ${e.dialect}" }

                    val dialectNames = dialects.map { it.dialectName }
                    val dialect = e.dialect
                    val check = when {
                        dialect.name in dialectNames -> true
                        dialect is H2Dialect && dialect.delegatedDialectNameProvider != null ->
                            dialect.delegatedDialectNameProvider!!.dialectName in dialectNames
                        else -> false
                    }
                    check.shouldBeTrue()
                }
            }

            // separator must be specified by PostgreSQL and SQL Server
            users.name.groupConcat().checkExcept(PostgreSQLDialect, PostgreSQLNGDialect, SQLServerDialect) {
                it.size shouldBeEqualTo 3
            }

            users.name.groupConcat(separator = ", ").checkExcept {
                it.size shouldBeEqualTo 3
                it["St. Petersburg"] shouldBeEqualTo "Andrey"

                when (currentDialectTest) {
                    // return order is arbitrary if no ORDER BY is specified
                    is MariaDBDialect, is SQLiteDialect ->
                        listOf("Sergey, Eugene", "Eugene, Sergey") shouldContain it["Munich"]

                    is MysqlDialect, is SQLServerDialect -> it["Munich"] shouldBeEqualTo "Eugene, Sergey"
                    else -> it["Munich"] shouldBeEqualTo "Sergey, Eugene"
                }

                it["Prague"].shouldBeNull()
            }

            users.name.groupConcat(separator = " | ", distinct = true)
                .checkExcept(OracleDialect, SQLiteDialect, SQLServerDialect) {

                    it.size shouldBeEqualTo 3
                    it["St. Petersburg"] shouldBeEqualTo "Andrey"

                    when (currentDialectTest) {
                        is MariaDBDialect ->
                            listOf("Sergey | Eugene", "Eugene | Sergey") shouldContain it["Munich"]

                        is MysqlDialect, is PostgreSQLDialect -> it["Munich"] shouldBeEqualTo "Eugene | Sergey"
                        is H2Dialect -> {
                            if (currentDialect.h2Mode == H2Dialect.H2CompatibilityMode.SQLServer) {
                                it["Munich"] shouldBeEqualTo "Sergey | Eugene"
                            } else {
                                it["Munich"] shouldBeEqualTo "Eugene | Sergey"
                            }
                        }

                        else -> it["Munich"] shouldBeEqualTo "Sergey | Eugene"
                    }
                    it["Prague"].shouldBeNull()
                }

            users.name.groupConcat(separator = " | ", orderBy = users.name to SortOrder.ASC).checkExcept {
                it.size shouldBeEqualTo 3
                it["St. Petersburg"] shouldBeEqualTo "Andrey"
                it["Munich"] shouldBeEqualTo "Eugene | Sergey"
                it["Prague"].shouldBeNull()
            }

            users.name.groupConcat(separator = " | ", orderBy = users.name to SortOrder.DESC).checkExcept {
                it.size shouldBeEqualTo 3
                it["St. Petersburg"] shouldBeEqualTo "Andrey"
                it["Munich"] shouldBeEqualTo "Sergey | Eugene"
                it["Prague"].shouldBeNull()
            }
        }
    }
}
