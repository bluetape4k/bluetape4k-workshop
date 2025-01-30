package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.SqlExpressionBuilder.case
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.booleanLiteral
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.notExists
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.orWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.vendors.OracleDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

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
     *        SELECT USERDATA.USER_ID,
     *               USERDATA.COMMENT,
     *               USERDATA."value"
     *          FROM USERDATA
     *         WHERE (USERDATA.USER_ID = USERS.ID)
     *           AND (USERDATA.COMMENT LIKE '%here%')
     *        )
     *  FROM USERS;                -- return FALSE
     *
     * SELECT EXISTS (
     *        SELECT USERDATA.USER_ID,
     *               USERDATA.COMMENT,
     *               USERDATA."value"
     *          FROM USERDATA
     *         WHERE (USERDATA.USER_ID = USERS.ID)
     *           AND (USERDATA.COMMENT LIKE '%here%')
     *       )
     *   FROM USERS
     *  WHERE USERS.ID = 'smth'    -- return TRUE
     * ```
     *
     * NOT EXISTS
     * ```sql
     * SELECT NOT EXISTS (
     *        SELECT USERDATA.USER_ID,
     *               USERDATA.COMMENT,
     *               USERDATA."value"
     *          FROM USERDATA
     *         WHERE (USERDATA.USER_ID = USERS.ID)
     *           AND (USERDATA.COMMENT LIKE '%here%')
     *        )
     *  FROM USERS;              -- return TRUE
     *
     * SELECT NOT EXISTS (
     *        SELECT USERDATA.USER_ID,
     *               USERDATA.COMMENT,
     *               USERDATA."value"
     *          FROM USERDATA
     *         WHERE (USERDATA.USER_ID = USERS.ID)
     *           AND (USERDATA.COMMENT LIKE '%here%')
     *        )
     *   FROM USERS
     *  WHERE USERS.ID = 'smth'   -- return FALSE
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
     * SELECT USERS.ID,
     *        USERS."name",
     *        USERS.CITY_ID,
     *        USERS.FLAGS
     *   FROM USERS
     *  WHERE EXISTS (
     *        SELECT USERDATA.USER_ID
     *          FROM USERDATA
     *         WHERE (USERDATA.USER_ID = USERS.ID)
     *           AND ((USERDATA.COMMENT LIKE '%here%') OR (USERDATA.COMMENT LIKE '%Sergey'))
     *        )
     *  ORDER BY USERS.ID ASC
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
                            .where {
                                (userData.userId eq users.id) and
                                        ((userData.comment like "%here%") or (userData.comment like "%Sergey"))
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
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS
     *   FROM USERS
     *  WHERE EXISTS (
     *          SELECT USERDATA.USER_ID
     *            FROM USERDATA
     *           WHERE (USERDATA.USER_ID = USERS.ID) AND (USERDATA.COMMENT LIKE '%here%')
     *        )
     *     OR EXISTS (
     *          SELECT USERDATA.USER_ID
     *            FROM USERDATA
     *           WHERE (USERDATA.USER_ID = USERS.ID) AND (USERDATA.COMMENT LIKE '%Sergey')
     *     )
     * ORDER BY USERS.ID ASC
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
