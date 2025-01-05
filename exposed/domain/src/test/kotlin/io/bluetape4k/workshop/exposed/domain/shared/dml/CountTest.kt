package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withSchemas
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CountTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * SELECT COUNT(*)
     *   FROM (
     *      SELECT DISTINCT CITIES.CITY_ID Cities_city_id,
     *             CITIES."name" Cities_name,
     *             USERS.ID Users_id,
     *             USERS."name" Users_name,
     *             USERS.CITY_ID Users_city_id,
     *             USERS.FLAGS Users_flags
     *        FROM CITIES INNER JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *   ) subquery
     * ```
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM (
     *      SELECT DISTINCT CITIES.CITY_ID Cities_city_id, USERS.ID Users_id
     *        FROM CITIES INNER JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *   ) subquery
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count works with Query that contains distinct and columns with same name from different tables`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            cities.innerJoin(users).selectAll().withDistinct().count() shouldBeEqualTo 3L

            cities.innerJoin(users).select(cities.id, users.id).withDistinct().count() shouldBeEqualTo 3L
        }
    }

    /**
     * Count for distinct
     * ```sql
     * SELECT COUNT(*) FROM (SELECT DISTINCT USERDATA.USER_ID UserData_user_id FROM USERDATA) subquery
     * ```
     *
     * Count for group by
     * ```sql
     * SELECT COUNT(*) FROM (SELECT MAX(USERDATA."value") exp0 FROM USERDATA GROUP BY USERDATA.USER_ID) subquery
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count returns right value for Query with group by`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, _, userData ->
            val uniqueUsersInData = userData.select(userData.userId).withDistinct().count()
            val sameQueryWithGrouping = userData
                .select(userData.value.max())
                .groupBy(userData.userId)
                .count()
            sameQueryWithGrouping shouldBeEqualTo uniqueUsersInData
        }

        withTables(testDb, OrgMemberships, Orgs) {
            val org1 = Org.new { name = "FOo" }
            OrgMembership.new { org = org1 }

            OrgMemberships.selectAll().count().toInt() shouldBeEqualTo 1
        }
    }

    /**
     * ```sql
     * SELECT COUNT(*)
     *   FROM (
     *          SELECT DISTINCT CUSTOM.TESTER.AMOUNT tester_amount
     *            FROM CUSTOM.TESTER
     *        ) subquery
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count alias with table schema`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }
        
        val custom = prepareSchemaForTest("custom")
        val tester = object: Table("custom.tester") {
            val amount = integer("amount")
        }

        withSchemas(testDb, custom) {
            SchemaUtils.create(tester)

            repeat(3) {
                tester.insert { it[amount] = 99 }
            }

            // count alias is generated for any query with distinct/groupBy/limit & throws if schema name included
            tester.select(tester.amount).withDistinct().count().toInt() shouldBeEqualTo 1

            SchemaUtils.drop(tester)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count with offset and limit`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }

        val tester = object: Table("tester") {
            val value = integer("value")
        }

        withTables(testDb, tester) {
            tester.batchInsert(listOf(1, 2, 3, 4, 5)) {
                this[tester.value] = it
            }

            tester.selectAll().count().toInt() shouldBeEqualTo 5

            // SELECT COUNT(*) FROM (SELECT TESTER."value" tester_value FROM TESTER LIMIT 2 OFFSET 1) subquery
            tester.selectAll().offset(1).limit(2).count().toInt() shouldBeEqualTo 2

            // SELECT COUNT(*) FROM (SELECT TESTER."value" tester_value FROM TESTER LIMIT 2) subquery
            tester.selectAll().limit(2).count().toInt() shouldBeEqualTo 2

            // SELECT COUNT(*) FROM (SELECT TESTER."value" tester_value FROM TESTER OFFSET 2) subquery
            tester.selectAll().offset(2).count().toInt() shouldBeEqualTo 3
        }
    }
}
