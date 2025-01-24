package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.currentTestDB
import io.bluetape4k.workshop.exposed.expectException
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeNull
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.delete
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteIgnoreWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.joinQuery
import org.jetbrains.exposed.sql.lastQueryAlias
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DeleteTest: AbstractExposedTest() {

    companion object: KLogging()

    private val limitNotSupported = TestDB.ALL_POSTGRES_LIKE

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete 01`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in limitNotSupported }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            userData.deleteAll()
            val userDataExists = userData.selectAll().count() > 0L
            userDataExists.shouldBeFalse()

            val smthId = users.select(users.id).where { users.name like "%thing" }.single()[users.id]
            smthId shouldBeEqualTo "smth"

            users.deleteWhere { users.name like "%thing" }

            val hasSmth = users.select(users.id).where { users.name.like("%thing") }.any()
            hasSmth.shouldBeFalse()

            if (currentDialectTest is MysqlDialect) {
                cities.selectAll().where { cities.id eq 1 }.count().toInt() shouldBeEqualTo 1
                expectException<ExposedSQLException> {
                    // a regular delete throws SQLIntegrityConstraintViolationException because Users reference Cities
                    // Cannot delete or update a parent row: a foreign key constraint fails
                    cities.deleteWhere { cities.id eq 1 }
                }

                // the error is now ignored and the record is skipped
                cities.deleteIgnoreWhere { cities.id eq 1 } shouldBeEqualTo 0

                cities.selectAll().where { cities.id eq 1 }.count().toInt() shouldBeEqualTo 1
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete table in context`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, userData ->
            userData.deleteAll()
            val userDataExists = userData.selectAll().any()
            userDataExists.shouldBeFalse()

            val smthId = users.select(users.id)
                .where { users.name like "%thing" }
                .single()[users.id]
            smthId shouldBeEqualTo "smth"

            // Now deleteWhere and deleteIgnoreWhere should bring the table it operates on into context
            users.deleteWhere { users.name like "%thing" }

            val hasSmth = users.selectAll()
                .where { users.name.like("%thing") }
                .firstOrNull()
            hasSmth.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with limit`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, _, userData ->
            if (currentTestDB in limitNotSupported) {
                expectException<UnsupportedByDialectException> {
                    userData.deleteWhere(limit = 1) { userData.value eq 20 }
                }
            } else {
                userData.deleteWhere(limit = 1) { userData.value eq 20 }
                userData.select(userData.userId, userData.value)
                    .where { userData.value eq 20 }
                    .let {
                        it.count().toInt() shouldBeEqualTo 1
                        val expected = if (currentDialectTest is H2Dialect) "smth" else "eugene"
                        it.single()[userData.userId] shouldBeEqualTo expected
                    }
            }
        }
    }

    /**
     * ### Delete with single join
     *
     * ```sql
     * MERGE INTO USERDATA USING USERS ON USERS.ID = USERDATA.USER_ID
     *  WHEN MATCHED AND USERDATA.USER_ID LIKE '%ey'
     *  THEN DELETE
     * ```
     *
     * ```sql
     * MERGE INTO USERDATA USING USERS ON USERS.ID = USERDATA.USER_ID
     *  WHEN MATCHED
     *  THEN DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with single join`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, userData ->
            val join = users innerJoin userData
            val query1 = join.selectAll().where { userData.userId like "%ey" }
            query1.count().toInt() shouldBeGreaterThan 0

            join.delete(userData) { userData.userId like "%ey" }
            query1.count().toInt() shouldBeEqualTo 0

            val query2 = join.selectAll()
            query2.count().toInt() shouldBeGreaterThan 0

            join.delete(userData)
            query2.count().toInt() shouldBeEqualTo 0
        }
    }

    /**
     * ### Delete with multiple alias joins
     *
     * MySQL
     * ```sql
     * DELETE stats
     *   FROM Cities towns
     *      INNER JOIN Users people ON towns.city_id = people.city_id
     *      INNER JOIN UserData stats ON people.id = stats.user_id
     *  WHERE towns.`name` = 'Munich'
     * ```
     *
     * Postgres
     * ```sql
     * DELETE FROM userdata stats
     *  USING cities towns, users people
     *  WHERE towns.city_id = people.city_id AND people.id = stats.user_id
     *    AND towns."name" = 'Munich'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with multiple alias joins`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_H2 }

        withCitiesAndUsers(testDb) { cities, users, userData ->
            val towns = cities.alias("towns")
            val people = users.alias("people")
            val stats = userData.alias("stats")

            val aliasedJoin = Join(towns)
                .innerJoin(people, { towns[cities.id] }, { people[users.cityId] })
                .innerJoin(stats, { people[users.id] }, { stats[userData.userId] })

            val query = aliasedJoin.selectAll().where { towns[cities.name] eq "Munich" }
            query.count().toInt() shouldBeGreaterThan 0

            aliasedJoin.delete(stats) { towns[cities.name] eq "Munich" }
            query.count().toInt() shouldBeEqualTo 0
        }
    }

    /**
     * ### Delete with join query
     *
     * MySQL
     * ```sql
     * DELETE UserData
     *   FROM UserData
     *        INNER JOIN (
     *              SELECT Users.id, Users.`name`
     *                FROM Users
     *               WHERE Users.city_id = 2
     *             ) q0 ON  (UserData.user_id = q0.id)
     *  WHERE q0.`name` LIKE '%ey'
     * ```
     *
     * Postgres
     * ```sql
     * DELETE
     *   FROM userdata
     *  USING (SELECT users.id, users."name" FROM users WHERE users.city_id = 2) q0
     *  WHERE (userdata.user_id = q0.id)
     *    AND q0."name" LIKE '%ey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with join query`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_H2 }

        withCitiesAndUsers(testDb) { _, users, userData ->
            val singleJoinQuery = userData.joinQuery(
                on = { userData.userId eq it[users.id] },
                joinPart = { users.select(users.id, users.name).where { users.cityId eq 2 } }
            )

            val joinCount = singleJoinQuery.selectAll().count().toInt()
            joinCount shouldBeGreaterThan 0

            val joinCountWithCondition = singleJoinQuery.selectAll()
                .where {
                    singleJoinQuery.lastQueryAlias!![users.name] like "%ey"
                }
                .count()
                .toInt()
            joinCountWithCondition shouldBeGreaterThan 0

            singleJoinQuery.delete(userData) {
                singleJoinQuery.lastQueryAlias!![users.name] like "%ey"
            }
            singleJoinQuery.selectAll().count().toInt() shouldBeEqualTo joinCount - joinCountWithCondition
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with join and limit`(testDb: TestDB) {
        // SQL Server, Oracle 에서만 지원한다.
        Assumptions.assumeTrue { testDb !in (TestDB.ALL_H2 + TestDB.ALL_MYSQL + TestDB.ALL_POSTGRES) }

        withCitiesAndUsers(testDb) { _, users, userData ->
            val join = users innerJoin userData
            val query = join.selectAll().where { userData.userId eq "smth" }
            val originalCount = query.count().toInt()
            originalCount shouldBeGreaterThan 1

            join.delete(userData, limit = 1) { userData.userId eq "smth" }
            query.count().toInt() shouldBeEqualTo originalCount - 1
        }
    }

    /**
     * ### Delete ignore with join
     *
     * MySQL
     * ```sql
     * DELETE IGNORE Users, UserData
     *   FROM Users INNER JOIN UserData ON Users.id = UserData.user_id
     *  WHERE Users.id = 'smth'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete ignore with join`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in TestDB.ALL_MYSQL }

        withCitiesAndUsers(testDb) { _, users, userData ->
            val join = users innerJoin userData
            val query = join.selectAll().where { userData.userId eq "smth" }
            query.count().toInt() shouldBeGreaterThan 0

            expectException<ExposedSQLException> {
                // 일반적으로는 UserData 테이블은 Users 테이블을 참조하고 있기 때문에, Users 테이블을 먼저 삭제할 수 없습니다.
                join.delete(users, userData) { users.id eq "smth" }
            }

            // UserData 참조 관련 에러는 무시된다. User 테이블은 건너뛰고 UserData 테이블의 행을 삭제된다.
            join.delete(users, userData, ignore = true) { users.id eq "smth" } shouldBeEqualTo 2

            query.count().toInt() shouldBeEqualTo 0
        }
    }
}
