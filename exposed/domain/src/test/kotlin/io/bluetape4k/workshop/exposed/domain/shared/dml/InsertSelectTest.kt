package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.autoIncColumnType
import org.jetbrains.exposed.sql.castTo
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.intParam
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.substring
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class InsertSelectTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * H2
     * ```sql
     * INSERT INTO CITIES ("name")
     * SELECT SUBSTRING(USERS."name", 1, 2)
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
     *  LIMIT 2
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select example 01`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { cities, users, _ ->
            val nextVal = cities.id.autoIncColumnType?.nextValExpression
            val substring = users.name.substring(1, 2)
            val slice = listOfNotNull(nextVal, substring)

            cities.insert(users.select(slice).orderBy(users.id).limit(2))

            val rows = cities.select(cities.name)
                .orderBy(cities.id, SortOrder.DESC)
                .limit(2)
                .toList()

            rows.size shouldBeEqualTo 2
            rows[0][cities.name] shouldBeEqualTo "An"   // Andrey
            rows[1][cities.name] shouldBeEqualTo "Al"   // Alex
        }
    }

    /**
     * H2
     * ```sql
     * INSERT INTO USERDATA (USER_ID, COMMENT, "value")
     * SELECT USERDATA.USER_ID, USERDATA.COMMENT, 42
     *   FROM USERDATA
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select example 02`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, _, userData ->
            val allUserData = userData.selectAll().count()

            userData.insert(
                userData.select(userData.userId, userData.comment, intParam(42))
            )

            val rows = userData.selectAll().where { userData.value eq 42 }
                .orderBy(userData.userId)
                .toList()

            rows.size.toLong() shouldBeEqualTo allUserData
        }
    }

    /**
     * H2
     * ```sql
     * INSERT INTO USERS (ID, "name", CITY_ID, FLAGS)
     * SELECT SUBSTRING(CAST(RANDOM() AS VARCHAR(255)), 1, 10), 'Foo', 1, 0 FROM USERS
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select example 03`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val userCount = users.selectAll().count()
            val nullableExpression = Random() as Expression<BigDecimal?>

            users.insert(
                users.select(
                    nullableExpression.castTo(VarCharColumnType()).substring(1, 10),
                    stringParam("Foo"),
                    intParam(1),
                    intLiteral(0)
                )
            )
            val rows = users.selectAll().where { users.name eq "Foo" }.toList()
            rows.size.toLong() shouldBeEqualTo userCount
        }
    }

    /**
     * H2
     * ```sql
     * INSERT INTO USERS ("name", ID)
     * SELECT 'Foo', SUBSTRING(CAST(RANDOM() AS VARCHAR(255)), 1, 10)
     *   FROM USERS
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select example 04`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val userCount = users.selectAll().count()

            users.insert(
                users.select(
                    stringParam("Foo"),
                    Random().castTo(VarCharColumnType()).substring(1, 10)
                ),
                columns = listOf(users.name, users.id)
            )

            val rows = users.selectAll().where { users.name eq "Foo" }.toList()
            rows.size.toLong() shouldBeEqualTo userCount
        }
    }

    /**
     * H2
     * ```sql
     * INSERT INTO USERS ("name", ID)
     * SELECT 'Foo', 'Foo' FROM USERS LIMIT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert-select with same columns in a query`(testDb: TestDB) {
        withCitiesAndUsers(testDb) { _, users, _ ->
            val fooParam = stringParam("Foo")

            users.insert(
                users.select(fooParam, fooParam).limit(1),
                columns = listOf(users.name, users.id)
            )

            users.selectAll()
                .where { users.name eq "Foo" }
                .count().toInt() shouldBeEqualTo 1
        }
    }
}
