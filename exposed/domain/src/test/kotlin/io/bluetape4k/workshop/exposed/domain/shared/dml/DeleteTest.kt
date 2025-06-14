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
import org.amshove.kluent.shouldNotBeEmpty
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.like
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.joinQuery
import org.jetbrains.exposed.v1.core.lastQueryAlias
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.v1.jdbc.delete
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteIgnoreWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DeleteTest: AbstractExposedTest() {

    companion object: KLogging()

    private val limitNotSupported = TestDB.ALL_POSTGRES

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, users, userData ->
            userData.deleteAll()
            userData.selectAll().count() shouldBeEqualTo 0L

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
                    // Users가 Cities를 참조합니다. 해당 City를 참조하는 User를 먼저 삭제해야 City를 삭제할 수 있습니다.
                    cities.deleteWhere { cities.id eq 1 }
                }

                // the error is now ignored and the record is skipped
                // 에러가 무시되고 레코드가 건너뛰어진다.
                cities.deleteIgnoreWhere { cities.id eq 1 } shouldBeEqualTo 0
                // 삭제되지 않았다.
                cities.selectAll().where { cities.id eq 1 }.count() shouldBeEqualTo 1L
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete table in context`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, userData ->
            userData.selectAll().shouldNotBeEmpty()

            /**
             * ```sql
             * DELETE FROM userdata
             * ```
             */
            userData.deleteAll()
            userData.selectAll().any().shouldBeFalse()  // no rows in UserData

            val smthId = users
                .select(users.id)
                .where { users.name like "%thing" }
                .single()[users.id]

            smthId shouldBeEqualTo "smth"

            /**
             * ```sql
             * DELETE FROM users WHERE users."name" LIKE '%thing'
             * ```
             */
            users.deleteWhere { users.name like "%thing" }

            users.selectAll()
                .where { users.name.like("%thing") }
                .any().shouldBeFalse()
        }
    }

    /**
     * ### Delete with limit
     *
     * ```sql
     * DELETE FROM USERDATA WHERE USERDATA."value" = 20 LIMIT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with limit`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, _, userData ->
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
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with single join`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, userData ->
            val join = users innerJoin userData

            val query1 = join.selectAll().where { userData.userId like "%ey" }
            query1.count() shouldBeGreaterThan 0L

            /**
             * `Users` 테이블과 `UserData` 테이블을 조인하여, `UserData` 테이블의 일부 행을 삭제합니다.
             *
             * MySQL V8:
             *
             * ```sql
             * DELETE UserData
             *   FROM Users INNER JOIN UserData ON Users.id = UserData.user_id
             *  WHERE UserData.user_id LIKE '%ey'
             * ```
             *
             * Postgres:
             *
             * ```sql
             * DELETE
             *   FROM userdata USING users
             *  WHERE users.id = userdata.user_id
             *    AND userdata.user_id LIKE '%ey'
             * ```
             */
            join.delete(userData) { userData.userId like "%ey" }
            query1.count() shouldBeEqualTo 0L

            val query2 = join.selectAll()
            query2.count() shouldBeGreaterThan 0L

            /**
             * Users 테이블과 UserData 테이블을 조인하여, UserData 테이블의 모든 행을 삭제합니다.
             *
             * MySQL V8:
             *
             * ```sql
             * DELETE UserData
             *   FROM Users INNER JOIN UserData ON Users.id = UserData.user_id
             * ```
             *
             * Postgres:
             *
             * ```sql
             * DELETE FROM userdata USING users WHERE users.id = userdata.user_id
             * ```
             */
            join.delete(userData)
            query2.count() shouldBeEqualTo 0L
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
    fun `delete with multiple alias joins`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2 }

        withCitiesAndUsers(testDB) { cities, users, userData ->
            val towns = cities.alias("towns")
            val people = users.alias("people")
            val stats = userData.alias("stats")

            val aliasedJoin = Join(towns)
                .innerJoin(people, { towns[cities.id] }, { people[users.cityId] })
                .innerJoin(stats, { people[users.id] }, { stats[userData.userId] })

            val query = aliasedJoin
                .selectAll()
                .where { towns[cities.name] eq "Munich" }

            query.count() shouldBeGreaterThan 0L

            aliasedJoin.delete(stats) { towns[cities.name] eq "Munich" }

            query.count() shouldBeEqualTo 0L
        }
    }

    /**
     * ### Delete with join sub query (`joinQuery`)
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with join query`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2 }

        withCitiesAndUsers(testDB) { _, users, userData ->
            val singleJoinQuery = userData.joinQuery(
                on = { userData.userId eq it[users.id] },
                joinPart = { users.select(users.id, users.name).where { users.cityId eq 2 } }
            )

            val joinCount = singleJoinQuery.selectAll().count()
            joinCount shouldBeGreaterThan 0

            val joinCountWithCondition = singleJoinQuery.selectAll()
                .where {
                    singleJoinQuery.lastQueryAlias!![users.name] like "%ey"
                }
                .count()

            joinCountWithCondition shouldBeGreaterThan 0L

            /**
             * MySQL V8:
             * ```sql
             * DELETE UserData
             *   FROM UserData INNER JOIN (
             *      SELECT Users.id,
             *             Users.`name`
             *        FROM Users
             *       WHERE Users.city_id = 2
             *   ) q0 ON  (UserData.user_id = q0.id)
             *  WHERE q0.`name` LIKE '%ey'
             * ```
             *
             * Postgres:
             * ```sql
             * DELETE FROM userdata
             *  USING (SELECT users.id,
             *                users."name"
             *           FROM users
             *          WHERE users.city_id = 2
             *        ) q0
             *  WHERE (userdata.user_id = q0.id)
             *    AND q0."name" LIKE '%ey'
             * ```
             */
            singleJoinQuery.delete(userData) {
                singleJoinQuery.lastQueryAlias!![users.name] like "%ey"
            }
            singleJoinQuery.selectAll().count() shouldBeEqualTo joinCount - joinCountWithCondition
        }
    }

    /**
     * ### Delete with join and limit
     *
     * **SQL Server, Oracle 에서만 지원한다.**
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete with join and limit`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_H2 + TestDB.ALL_MYSQL_MARIADB + TestDB.ALL_POSTGRES) }

        withCitiesAndUsers(testDB) { _, users, userData ->
            val join = users innerJoin userData
            val query = join.selectAll().where { userData.userId eq "smth" }
            val originalCount = query.count()
            originalCount shouldBeGreaterThan 1L

            join.delete(userData, limit = 1) { userData.userId eq "smth" }
            query.count() shouldBeEqualTo originalCount - 1L
        }
    }

    /**
     * ### Join 을 사용하여 DELETE 를 수행할 때, 참조 위배 등의 예외 발생 시 무시한다.
     *
     * `delete(table, ignore = true) { ... }` 와 같이 `ignore = true` 를 지정하여, 참조 위배 등의 예외를 무시할 수 있다.
     * 단, MySQL 에서만 지원한다.
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
    fun `delete ignore with join`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_MYSQL_MARIADB }

        withCitiesAndUsers(testDB) { _, users, userData ->
            val join = users innerJoin userData
            val query = join.selectAll().where { userData.userId eq "smth" }
            query.count() shouldBeGreaterThan 0L

            expectException<ExposedSQLException> {
                // UserData 테이블은 Users 테이블을 참조하고 있기 때문에, Users 테이블을 먼저 삭제할 수 없습니다.
                join.delete(users, userData) { users.id eq "smth" }
            }

            // ignore=true 를 지정하여, UserData 참조 관련 에러는 무시된다.
            // User 테이블은 건너뛰고 UserData 테이블의 행을 삭제된다.
            join.delete(users, userData, ignore = true) { users.id eq "smth" } shouldBeEqualTo 2

            query.count().toInt() shouldBeEqualTo 0
        }
    }
}
