package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.case
import org.jetbrains.exposed.v1.core.booleanLiteral
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.OracleDialect
import org.jetbrains.exposed.v1.core.vendors.SQLServerDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.orWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * `EXISTS` 키워드를 사용하는 예제입니다.
 */
class ExistsTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * "EXISTS" 키워드를 이용한 예제입니다.
     *
     * MySQL:
     * ```sql
     * SELECT Users.id, Users.`name`, Users.city_id, Users.flags
     *   FROM Users
     *  WHERE EXISTS (
     *          SELECT UserData.user_id
     *            FROM UserData
     *           WHERE (UserData.user_id = Users.id)
     *             AND (UserData.comment LIKE '%here%')
     *       )
     * ```
     *
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE EXISTS (
     *          SELECT userdata.user_id
     *            FROM userdata
     *           WHERE (userdata.user_id = users.id)
     *             AND (userdata."comment" LIKE '%here%')
     *        )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exists example 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, userData ->
            val rows = users.selectAll()
                .where {
                    exists(
                        userData
                            .select(userData.userId)
                            .where { userData.userId eq users.id }
                            .andWhere { userData.comment like "%here%" }
                    )
                }.toList()

            rows shouldHaveSize 1
            rows.single()[users.name] shouldBeEqualTo "Something"
        }
    }

    /**
     * "EXISTS", "NOT EXISTS" 키워드를 SELECT 절에 사용하는 예제
     *
     * EXISTS
     * ```sql
     * SELECT EXISTS (
     *          SELECT userdata.user_id, userdata."comment", userdata."value"
     *            FROM userdata
     *           WHERE (userdata.user_id = users.id)
     *             AND (userdata."comment" LIKE '%here%')
     *         )
     *   FROM users;
     *
     * SELECT EXISTS (
     *          SELECT userdata.user_id, userdata."comment", userdata."value"
     *            FROM userdata
     *           WHERE (userdata.user_id = users.id)
     *             AND (userdata."comment" LIKE '%here%')
     *        )
     *   FROM users
     *  WHERE users.id = 'smth';
     * ```
     *
     * NOT EXISTS
     * ```sql
     * SELECT NOT EXISTS (
     *          SELECT userdata.user_id, userdata."comment", userdata."value"
     *            FROM userdata
     *           WHERE (userdata.user_id = users.id)
     *             AND (userdata."comment" LIKE '%here%')
     *        )
     *   FROM users;
     *
     * SELECT NOT EXISTS (
     *          SELECT userdata.user_id, userdata."comment", userdata."value"
     *            FROM userdata
     *           WHERE (userdata.user_id = users.id)
     *             AND (userdata."comment" LIKE '%here%')
     *        )
     *   FROM users
     *  WHERE users.id = 'smth';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exists in a slice`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, userData ->
            var exists: Expression<Boolean> = exists(
                userData
                    .selectAll()
                    .where { userData.userId eq users.id }
                    .andWhere { userData.comment like "%here%" }
            )
            if (currentDialectTest is OracleDialect || currentDialect is SQLServerDialect) {
                exists = case().When(exists, booleanLiteral(true)).Else(booleanLiteral(false))
            }

            users.select(exists).first()[exists].shouldBeFalse()
            users.select(exists)
                .where { users.id eq "smth" }
                .single()[exists].shouldBeTrue()

            var notExists: Expression<Boolean> = notExists(
                userData
                    .selectAll()
                    .where { userData.userId eq users.id }
                    .andWhere { userData.comment like "%here%" }
            )
            if (currentDialectTest is OracleDialect || currentDialect is SQLServerDialect) {
                notExists = case().When(exists, booleanLiteral(true)).Else(booleanLiteral(false))
            }

            users.select(notExists).first()[notExists].shouldBeTrue()

            users.select(notExists)
                .where { users.id eq "smth" }
                .single()[notExists].shouldBeFalse()

        }
    }

    /**
     * "EXISTS" 키워드를 WHERE 절에 이용한 예제입니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE EXISTS (SELECT userdata.user_id
     *                  FROM userdata
     *                 WHERE (userdata.user_id = users.id)
     *                   AND ((userdata."comment" LIKE '%here%') OR (userdata."comment" LIKE '%Sergey'))
     *               )
     *  ORDER BY users.id ASC;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exists examples 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, userData ->
            val rows = users
                .selectAll()
                .where {
                    exists(
                        userData.select(userData.userId)
                            .where { userData.userId eq users.id }
                            .andWhere {
                                (userData.comment like "%here%") or (userData.comment like "%Sergey")
                            }
                    )
                }
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 2
            rows[0][users.name] shouldBeEqualTo "Sergey"
            rows[1][users.name] shouldBeEqualTo "Something"
        }
    }

    /**
     * "EXISTS" 키워드를 WHERE 절에 이용한 예제입니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE EXISTS (SELECT userdata.user_id
     *                  FROM userdata
     *                 WHERE (userdata.user_id = users.id)
     *                   AND (userdata."comment" LIKE '%here%')
     *               )
     *     OR EXISTS (SELECT userdata.user_id
     *                  FROM userdata
     *                 WHERE (userdata.user_id = users.id)
     *                   AND (userdata."comment" LIKE '%Sergey')
     *               )
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exists examples 03`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, userData ->
            val rows = users
                .selectAll()
                .where {
                    exists(
                        userData
                            .select(userData.userId)
                            .where { userData.userId eq users.id }
                            .andWhere { userData.comment like "%here%" }
                    )
                }
                .orWhere {
                    exists(
                        userData
                            .select(userData.userId)
                            .where { userData.userId eq users.id }
                            .andWhere { userData.comment like "%Sergey" }
                    )
                }
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 2
            rows[0][users.name] shouldBeEqualTo "Sergey"
            rows[1][users.name] shouldBeEqualTo "Something"
        }
    }
}
