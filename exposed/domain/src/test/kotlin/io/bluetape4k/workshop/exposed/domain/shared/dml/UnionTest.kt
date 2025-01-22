package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.SetOperation
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.except
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.intersect
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.union
import org.jetbrains.exposed.sql.unionAll
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MariaDBDialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class UnionTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * LIMIT 을 적용한 UNION 쿼리를 테스트합니다.
     *
     * ```sql
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS FROM USERS WHERE USERS.ID = 'andrey'
     * UNION
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS FROM USERS WHERE USERS.ID = 'sergey'
     * LIMIT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union within limit`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }

            val rows = andreyQuery.union(sergeyQuery)
                .limit(1)
                .map { it[users.id] }

            rows shouldHaveSize 1
            rows.first() shouldBeEqualTo "andrey"
        }
    }

    /**
     * LIMIT 과 OFFSET 을 적용한 UNION 쿼리를 테스트합니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users WHERE users.id = 'andrey'
     *  UNION
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users WHERE users.id = 'sergey'
     *  LIMIT 1
     * OFFSET 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union with limit and offset`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }

            val rows = andreyQuery
                .union(sergeyQuery)
                .limit(1)
                .offset(1)
                .map { it[users.id] }

            rows shouldHaveSize 1
            rows.first() shouldBeEqualTo "sergey"
        }
    }

    /**
     * UNION 쿼리의 COUNT 를 테스트합니다.
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM (
     *      SELECT users.id, users."name", users.city_id, users.flags
     *        FROM users WHERE users.id = 'andrey'
     *      UNION
     *      SELECT users.id, users."name", users.city_id, users.flags
     *        FROM users WHERE users.id = 'sergey'
     *   ) subquery
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count of union query`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }

            andreyQuery.union(sergeyQuery).count().toInt() shouldBeEqualTo 2
        }
    }

    /**
     * ORDER BY 를 적용한 UNION 쿼리를 테스트합니다.
     *
     * withDistinct(true) 를 적용한 경우:
     * ```sql
     * SELECT users.id id_alias FROM users WHERE users.id IN ('andrey', 'sergey')
     * UNION
     * SELECT users.id id_alias FROM users WHERE users.id IN ('andrey', 'sergey')
     * ORDER BY id_alias DESC
     * ```
     *
     * withDistinct(false) 를 적용한 경우:
     * ```sql
     * SELECT users.id id_alias FROM users WHERE users.id IN ('andrey', 'sergey')
     * UNION ALL
     * SELECT users.id id_alias FROM users WHERE users.id IN ('andrey', 'sergey')
     * ORDER BY id_alias DESC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union within orderBy`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val idAlias = users.id.alias("id_alias")

            val idQuery: Query = users.select(idAlias).where { users.id inList setOf("andrey", "sergey") }
            val union: SetOperation = idQuery.union(idQuery).orderBy(idAlias, SortOrder.DESC)

            union.map { it[idAlias] } shouldBeEqualTo listOf("sergey", "andrey")

            union.withDistinct(false).map { it[idAlias] } shouldBeEqualTo listOf("sergey", "sergey", "andrey", "andrey")
        }
    }

    /**
     * UNION 쿼리의 DISTINCT 를 테스트합니다.
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * UNION
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'sergey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union of two queries`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }


            val rows = andreyQuery.union(sergeyQuery).map { it[users.id] }

            rows shouldHaveSize 2
            rows shouldBeEqualTo listOf("andrey", "sergey")
        }
    }

    /**
     * UNION ALL, INTERSECT 쿼리를 테스트합니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags FROM users
     * UNION ALL
     * SELECT users.id, users."name", users.city_id, users.flags FROM users
     * INTERSECT
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'sergey'
     * ```
     *
     * intersected users: [andrey, sergey, eugene, alex, smth, sergey]
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `intersect with three queries`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val usersQuery = users.selectAll()
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }

            // expected users id: [andrey, sergey, eugene, alex, smth, sergey]
            val expectedUsers = usersQuery.map { it[users.id] } + "sergey"


            val intersectAppliedFirst = when (currentDialect) {
                is PostgreSQLDialect,
                is SQLServerDialect,
                is MariaDBDialect,
                             -> true

                is H2Dialect -> (currentDialect as H2Dialect).isSecondVersion
                else         -> false
            }

            val rows = usersQuery
                .unionAll(usersQuery)
                .intersect(sergeyQuery)
                .map { it[users.id] }

            log.debug { "intersected users: $rows" }

            if (intersectAppliedFirst) {
                rows shouldHaveSize 6
                rows shouldBeEqualTo expectedUsers
            } else {
                rows shouldHaveSize 1
                rows.single() shouldBeEqualTo "sergey"
            }
        }
    }

    /**
     * UNION ALL, EXCEPT 쿼리를 테스트합니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags FROM users
     * EXCEPT
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'sergey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `except with two query`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val usersQuery = users.selectAll()
            val expectedUsers = usersQuery.map { it[users.id] } - "sergey"
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }

            val result = usersQuery.except(sergeyQuery).map { it[users.id] }

            result shouldHaveSize 4
            result shouldContainSame expectedUsers
        }
    }

    /**
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags FROM users
     * UNION ALL
     * SELECT users.id, users."name", users.city_id, users.flags FROM users
     * EXCEPT
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'sergey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `except of three queries`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val usersQuery = users.selectAll()
            val expectedUsers = usersQuery.map { it[users.id] } - "sergey"
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }

            val result = usersQuery
                .unionAll(usersQuery)
                .except(sergeyQuery)
                .map { it[users.id] }

            result shouldHaveSize 4
            result shouldContainSame expectedUsers
        }
    }

    /**
     * 복수의 EXCEPT 쿼리 적용
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags FROM users
     * EXCEPT
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'sergey'
     * EXCEPT
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'andrey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `except of two excepts queries`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val usersQuery = users.selectAll()
            val expectedUsers = usersQuery.map { it[users.id] } - "sergey" - "andrey"
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }

            val result = usersQuery
                .except(sergeyQuery)
                .except(andreyQuery)
                .map { it[users.id] }

            result shouldHaveSize 3
            result shouldContainSame expectedUsers
        }
    }

    /**
     * union multiple queries
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'andrey'
     * UNION
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'sergey'
     * UNION
     * SELECT users.id, users."name", users.city_id, users.flags FROM users WHERE users.id = 'eugene'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union of more than two query`(testDb: TestDB) {
        // Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }
            val sergeyQuery = users.selectAll().where { users.id eq "sergey" }
            val eugeneQuery = users.selectAll().where { users.id eq "eugene" }

            val result = andreyQuery
                .union(sergeyQuery)
                .union(eugeneQuery)
                .map { it[users.id] }

            result shouldHaveSize 3
            result shouldContainSame listOf("andrey", "sergey", "eugene")
        }
    }

    /**
     * union of sorted queries
     *
     * ```sql
     * (
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id IN ('andrey', 'sergey')
     *  ORDER BY users.id DESC
     * )
     *
     *  UNION ALL
     *
     * (
     *  SELECT users.id, users."name", users.city_id, users.flags
     *     FROM users
     *    WHERE users.id IN ('andrey', 'sergey')
     *    ORDER BY users.id DESC
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union of sorted queries`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyOrSergeyQuery = users.selectAll()
                .where { users.id inList setOf("andrey", "sergey") }
                .orderBy(users.id, SortOrder.DESC)

            if (currentDialect.supportsSubqueryUnions) {
                val result = andreyOrSergeyQuery
                    .union(andreyOrSergeyQuery)
                    .withDistinct(false)
                    .map { it[users.id] }

                result shouldHaveSize 4
                result.all { it in setOf("andrey", "sergey") }.shouldBeTrue()
            } else {
                expectException<IllegalArgumentException> {
                    andreyOrSergeyQuery.union(andreyOrSergeyQuery)
                }
            }
        }
    }

    /**
     * union of limited queries
     *
     * ```sql
     * (
     *  SELECT users.id, users."name", users.city_id, users.flags
     *    FROM users
     *   WHERE users.id IN ('andrey', 'sergey')
     *   LIMIT 1
     * )
     * UNION ALL
     * (
     *  SELECT users.id, users."name", users.city_id, users.flags
     *    FROM users
     *   WHERE users.id IN ('andrey', 'sergey')
     *   LIMIT 1
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union of limited queries`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyOrSergeyQuery = users.selectAll()
                .where { users.id inList setOf("andrey", "sergey") }
                .limit(1)

            if (currentDialect.supportsSubqueryUnions) {
                val result = andreyOrSergeyQuery
                    .unionAll(andreyOrSergeyQuery)
                    .map { it[users.id] }

                result shouldHaveSize 2
                result.all { it == "andrey" }.shouldBeTrue()
            } else {
                expectException<IllegalArgumentException> {
                    andreyOrSergeyQuery.union(andreyOrSergeyQuery)
                }
            }
        }
    }

    /**
     * union of limited queries
     *
     * ```sql
     * (
     *  SELECT users.id, users."name", users.city_id, users.flags
     *    FROM users
     *   WHERE users.id IN ('andrey', 'sergey')
     *   ORDER BY users.id DESC
     *   LIMIT 1
     * )
     * UNION ALL
     * (
     *  SELECT users.id, users."name", users.city_id, users.flags
     *    FROM users
     *   WHERE users.id IN ('andrey', 'sergey')
     *   ORDER BY users.id DESC
     *   LIMIT 1
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union of sorted and limited queries`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, userData ->
            val andreyOrSergeyQuery = users.selectAll()
                .where { users.id inList setOf("andrey", "sergey") }
                .orderBy(users.id, SortOrder.DESC)
                .limit(1)

            if (currentDialect.supportsSubqueryUnions) {
                val result = andreyOrSergeyQuery
                    .unionAll(andreyOrSergeyQuery)
                    .map { it[users.id] }

                result shouldHaveSize 2
                result.all { it == "sergey" }.shouldBeTrue()
            } else {
                expectException<IllegalArgumentException> {
                    andreyOrSergeyQuery.union(andreyOrSergeyQuery)
                }
            }
        }
    }

    /**
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     *  UNION
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union with distinct results`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }

            val result = andreyQuery
                .union(andreyQuery)
                .map { it[users.id] }

            result shouldBeEqualTo listOf("andrey")
        }
    }

    /**
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * UNION ALL
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union with all results`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }

            val result = andreyQuery
                .unionAll(andreyQuery)
                .map { it[users.id] }
            result shouldBeEqualTo listOf("andrey", "andrey")
        }
    }

    /**
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * UNION ALL
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * UNION ALL
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union with all results of three queries`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val andreyQuery = users.selectAll().where { users.id eq "andrey" }

            val result = andreyQuery
                .unionAll(andreyQuery)
                .unionAll(andreyQuery)
                .map { it[users.id] }

            result shouldBeEqualTo List(3) { "andrey" }
        }
    }

    /**
     * ```sql
     * SELECT users.id, 10 exp1, 'aaa' exp2
     *   FROM users
     *  WHERE users.id = 'andrey'
     * UNION ALL
     * SELECT users.id, 100, 'bbb'
     *   FROM users
     *  WHERE users.id = 'andrey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union with expressions`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val exp1a = intLiteral(10)
            val exp1b = intLiteral(100)
            val exp2a = stringLiteral("aaa")
            val exp2b = stringLiteral("bbb")
            val andreyQuery1 = users.select(users.id, exp1a, exp2a).where { users.id eq "andrey" }
            val andreyQuery2 = users.select(users.id, exp1b, exp2b).where { users.id eq "andrey" }

            val unionAlias = andreyQuery1.unionAll(andreyQuery2)
            val result = unionAlias.map { Triple(it[users.id], it[exp1a], it[exp2a]) }
            result shouldBeEqualTo listOf(
                Triple("andrey", 10, "aaa"),
                Triple("andrey", 100, "bbb")
            )
        }
    }

    /**
     * ```sql
     * SELECT unionAlias.id, exp1, exp2
     *   FROM (
     *      SELECT users.id, 10 exp1, 'aaa' exp2
     *        FROM users
     *       WHERE users.id = 'andrey'
     *      UNION ALL
     *      SELECT users.id, 100, 'bbb'
     *        FROM users
     *       WHERE users.id = 'andrey'
     *   ) unionAlias
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `union with expression and alias`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val exp1a = intLiteral(10)
            val exp1b = intLiteral(100)
            val exp2a = stringLiteral("aaa")
            val exp2b = stringLiteral("bbb")
            val andreyQuery1 = users.select(users.id, exp1a, exp2a).where { users.id eq "andrey" }
            val andreyQuery2 = users.select(users.id, exp1b, exp2b).where { users.id eq "andrey" }

            val unionAlias = andreyQuery1.unionAll(andreyQuery2).alias("unionAlias")

            val result = unionAlias.selectAll()
                .map {
                    Triple(it[unionAlias[users.id]], it[unionAlias[exp1a]], it[unionAlias[exp2a]])
                }

            result shouldBeEqualTo listOf(
                Triple("andrey", 10, "aaa"),
                Triple("andrey", 100, "bbb")
            )
        }
    }
}
