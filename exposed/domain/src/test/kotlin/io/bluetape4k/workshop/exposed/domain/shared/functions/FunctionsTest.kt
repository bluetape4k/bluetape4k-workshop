package io.bluetape4k.workshop.exposed.domain.shared.functions

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.asBigDecimal
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.shared.dml.DMLTestData
import io.bluetape4k.workshop.exposed.domain.shared.dml.DMLTestData.Cities
import io.bluetape4k.workshop.exposed.domain.shared.dml.withCitiesAndUsers
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.CharLength
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.Concat
import org.jetbrains.exposed.v1.core.CustomLongFunction
import org.jetbrains.exposed.v1.core.CustomOperator
import org.jetbrains.exposed.v1.core.CustomStringFunction
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UpperCase
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.charLength
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.function
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.intParam
import org.jetbrains.exposed.v1.core.locate
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.orIfNotNull
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.core.substring
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.core.upperCase
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.SQLServerDialect
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class FunctionsTest: AbstractFunctionsTest() {

    companion object: KLogging()

    /**
     * sum function
     *
     * ```sql
     * SELECT SUM(cities.city_id) FROM cities
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `calc function`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val row = cities.select(cities.id.sum()).single()
            row[cities.id.sum()] shouldBeEqualTo 6
        }
    }

    /**
     * custom function
     *
     * ```sql
     * SELECT users.id,
     *        SUM((cities.city_id + userdata."value"))
     *   FROM users
     *      INNER JOIN userdata ON users.id = userdata.user_id
     *      INNER JOIN cities ON cities.city_id = users.city_id
     *  GROUP BY users.id
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `calc function 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, users, userData ->
            val sum = Expression.build {
                Sum(cities.id + userData.value, IntegerColumnType())
            }

            val rows = (users innerJoin userData innerJoin cities)
                .select(users.id, sum)
                .groupBy(users.id)
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 2

            rows[0][users.id] shouldBeEqualTo "eugene"
            rows[0][sum] shouldBeEqualTo 22

            rows[1][users.id] shouldBeEqualTo "sergey"
            rows[1][sum] shouldBeEqualTo 32
        }
    }

    /**
     * Cacl function with DecimalColumnType
     *
     * ```sql
     * SELECT users.id,
     *        SUM(((cities.city_id * 100) + (userdata."value" / 10))),
     *        (SUM(((cities.city_id * 100) + (userdata."value" / 10))) / 100),
     *        (SUM(((cities.city_id * 100) + (userdata."value" / 10))) % 100)
     *   FROM users
     *          INNER JOIN userdata ON users.id = userdata.user_id
     *          INNER JOIN cities ON cities.city_id = users.city_id
     *  GROUP BY users.id
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `calc function 03`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, users, userData ->
            val sum = Expression.build {
                Sum(cities.id * 100 + userData.value / 10, IntegerColumnType())
            }
            val sum2 = Expression.build {
                Sum(
                    cities.id.intToDecimal() * 100.0.toBigDecimal() + userData.value.intToDecimal() / 10.0.toBigDecimal(),
                    DecimalColumnType(10, 2)
                )
            }
            val div = Expression.build { sum / 100 }
            val mod = Expression.build { sum mod 100 }

            val rows = (users innerJoin userData innerJoin cities)
                .select(users.id, sum, div, mod)
                .groupBy(users.id)
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 2

            rows[0][users.id] shouldBeEqualTo "eugene"
            rows[0][sum] shouldBeEqualTo 202
            rows[0][div] shouldBeEqualTo 2
            rows[0][mod] shouldBeEqualTo 2

            rows[1][users.id] shouldBeEqualTo "sergey"
            rows[1][sum] shouldBeEqualTo 203
            rows[1][div] shouldBeEqualTo 2
            rows[1][mod] shouldBeEqualTo 3
        }
    }

    /**
     * Sum function with DecimalColumnType
     *
     * ```sql
     * SELECT users.id,
     *        SUM(((cities.city_id * 100.0) + (userdata."value" / 10.0))),
     *        (SUM(((cities.city_id * 100.0) + (userdata."value" / 10.0))) / 100.0),
     *        (SUM(((cities.city_id * 100.0) + (userdata."value" / 10.0))) % 100.0)
     *   FROM users INNER JOIN userdata ON users.id = userdata.user_id
     *              INNER JOIN cities ON cities.city_id = users.city_id
     *  GROUP BY users.id
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `calc function 02 - Decimal`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, users, userData ->
            val sum = Expression.build {
                Sum(
                    cities.id.intToDecimal() * 100.0.toBigDecimal() + userData.value.intToDecimal() / 10.0.toBigDecimal(),
                    DecimalColumnType(15, 0)
                )
            }
            val div = Expression.build { sum / 100.0.toBigDecimal() }
            val mod = Expression.build { sum mod 100.0.toBigDecimal() }

            val rows = (users innerJoin userData innerJoin cities)
                .select(users.id, sum, div, mod)
                .groupBy(users.id)
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 2

            rows.forEachIndexed { index, row ->
                log.debug { "rows[$index]=$row" }
            }

            rows[0][users.id] shouldBeEqualTo "eugene"
            rows[0][sum].asBigDecimal() shouldBeEqualTo 202.0.toBigDecimal()
            rows[0][div].asBigDecimal() shouldBeEqualTo 2.0.toBigDecimal()
            rows[0][mod].asBigDecimal() shouldBeEqualTo 2.0.toBigDecimal()

            rows[1][users.id] shouldBeEqualTo "sergey"
            rows[1][sum].asBigDecimal() shouldBeEqualTo 203.0.toBigDecimal()
            rows[1][div].asBigDecimal() shouldBeEqualTo 2.0.toBigDecimal()
            rows[1][mod].asBigDecimal() shouldBeEqualTo 3.0.toBigDecimal()
        }
    }

    /**
     * [Expression.build] with alias
     *
     * ```sql
     * SELECT test_mod_on_pk.id,
     *        (test_mod_on_pk.id % 3) shard1,
     *        (test_mod_on_pk.id % 3) shard2,
     *        (test_mod_on_pk.id % test_mod_on_pk.other) shard3,
     *        (test_mod_on_pk.other % test_mod_on_pk.id) shard4
     *   FROM test_mod_on_pk
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `rem on numeric PK should work`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS test_mod_on_pk (
         *      id SERIAL PRIMARY KEY,
         *      other SMALLINT NOT NULL
         * )
         * ```
         */
        val table = object: IntIdTable("test_mod_on_pk") {
            val otherColumn = short("other")
        }

        withTables(testDB, table) {
            repeat(5) {
                table.insert {
                    it[otherColumn] = 4
                }
            }

            // HINT: IdentityID 컬럼에 대해서도 이렇게 연산을 할 수 있네 ???
            val modOnPk1 = Expression.build { table.id % 3 }.alias("shard1")
            val modOnPk2 = Expression.build { table.id % intLiteral(3) }.alias("shard2")
            val modOnPk3 = Expression.build { table.id % table.otherColumn }.alias("shard3")
            val modOnPk4 = Expression.build { table.otherColumn % table.id }.alias("shard4")

            val rows = table.select(table.id, modOnPk1, modOnPk2, modOnPk3, modOnPk4).last()

            rows[modOnPk1] shouldBeEqualTo 2   // 5 % 3 = 2
            rows[modOnPk2] shouldBeEqualTo 2   // 5 % 3 = 2
            rows[modOnPk3] shouldBeEqualTo 1   // 5 % 4 = 1
            rows[modOnPk4] shouldBeEqualTo 4   // 4 % 5 = 4
        }
    }

    /**
     * [Expression.build] with alias for numeric PK
     *
     * ```sql
     * SELECT test_mod_on_pk.id,
     *        (test_mod_on_pk.id % 3) shard1,
     *        (test_mod_on_pk.id % 3) shard2,
     *        (test_mod_on_pk.id % test_mod_on_pk.other) shard3,
     *        (test_mod_on_pk.other % test_mod_on_pk.id) shard4
     *   FROM test_mod_on_pk
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mod on numeric PK should work`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS test_mod_on_pk (
         *      id SERIAL PRIMARY KEY,
         *      other SMALLINT NOT NULL
         * )
         * ```
         */
        val table = object: IntIdTable("test_mod_on_pk") {
            val otherColumn = short("other")
        }

        withTables(testDB, table) {
            repeat(5) {
                table.insert {
                    it[otherColumn] = 4
                }
            }

            val modOnPk1 = Expression.build { table.id mod 3 }.alias("shard1")
            val modOnPk2 = Expression.build { table.id mod intLiteral(3) }.alias("shard2")
            val modOnPk3 = Expression.build { table.id mod table.otherColumn }.alias("shard3")
            val modOnPk4 = Expression.build { table.otherColumn mod table.id }.alias("shard4")

            val rows = table.select(table.id, modOnPk1, modOnPk2, modOnPk3, modOnPk4).last()

            rows[modOnPk1] shouldBeEqualTo 2   // 5 % 3 = 2
            rows[modOnPk2] shouldBeEqualTo 2   // 5 % 3 = 2
            rows[modOnPk3] shouldBeEqualTo 1   // 5 % 4 = 1
            rows[modOnPk4] shouldBeEqualTo 4   // 4 % 5 = 4
        }
    }

    /**
     * bitwiseAnd function
     *
     * ```sql
     * SELECT (users.flags & 1),
     *        (users.flags & 1) = 1
     *   FROM users
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bitwiseAnd 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            val adminFlag = DMLTestData.Users.Flags.IS_ADMIN
            val adminAndFlgsExpr = Expression.build { users.flags bitwiseAnd adminFlag }
            val adminEq = Expression.build { adminAndFlgsExpr eq adminFlag }
            val toSlice = listOfNotNull(adminAndFlgsExpr, adminEq)

            val rows = users.select(toSlice).orderBy(users.id).toList()

            rows shouldHaveSize 5

            rows[0][adminAndFlgsExpr] shouldBeEqualTo 0
            rows[1][adminAndFlgsExpr] shouldBeEqualTo 1
            rows[2][adminAndFlgsExpr] shouldBeEqualTo 0
            rows[3][adminAndFlgsExpr] shouldBeEqualTo 1
            rows[4][adminAndFlgsExpr] shouldBeEqualTo 0

            rows[0][adminEq].shouldBeFalse()
            rows[1][adminEq].shouldBeTrue()
            rows[2][adminEq].shouldBeFalse()
            rows[3][adminEq].shouldBeTrue()
            rows[4][adminEq].shouldBeFalse()
        }
    }

    /**
     * bitwiseAnd function
     *
     * ```sql
     * SELECT (users.flags & 1),
     *        (users.flags & 1) = 1
     *   FROM users
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bitwiseAnd 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            val adminFlag = DMLTestData.Users.Flags.IS_ADMIN
            val adminAndFlgsExpr = Expression.build { users.flags bitwiseAnd intLiteral(adminFlag) }
            val adminEq = Expression.build { adminAndFlgsExpr eq adminFlag }
            val toSlice = listOfNotNull(adminAndFlgsExpr, adminEq)

            val rows = users.select(toSlice).orderBy(users.id).toList()

            rows shouldHaveSize 5

            rows[0][adminAndFlgsExpr] shouldBeEqualTo 0
            rows[1][adminAndFlgsExpr] shouldBeEqualTo 1
            rows[2][adminAndFlgsExpr] shouldBeEqualTo 0
            rows[3][adminAndFlgsExpr] shouldBeEqualTo 1
            rows[4][adminAndFlgsExpr] shouldBeEqualTo 0

            rows[0][adminEq].shouldBeFalse()
            rows[1][adminEq].shouldBeTrue()
            rows[2][adminEq].shouldBeFalse()
            rows[3][adminEq].shouldBeTrue()
            rows[4][adminEq].shouldBeFalse()
        }
    }

    /**
     * bitwiseOr function
     *
     * ```sql
     * SELECT users.id,
     *        (users.flags | 2)
     *  FROM users
     * ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bitwiseOr 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val extra = 0b10
            val flagsWithExtra = Expression.build { users.flags bitwiseOr extra }

            val rows = users.select(users.id, flagsWithExtra).orderBy(users.id).toList()

            rows shouldHaveSize 5
            rows[0][flagsWithExtra] shouldBeEqualTo 0b0010
            rows[1][flagsWithExtra] shouldBeEqualTo 0b0011
            rows[2][flagsWithExtra] shouldBeEqualTo 0b1010
            rows[3][flagsWithExtra] shouldBeEqualTo 0b1011
            rows[4][flagsWithExtra] shouldBeEqualTo 0b1010
        }
    }

    /**
     * bitwiseOr function
     *
     * ```sql
     * SELECT users.id,
     *        users.flags,
     *        (users.flags | 2)
     *   FROM users
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bitwiseOr 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val extra = 0b10
            val flagsWithExtra = Expression.build { users.flags bitwiseOr intLiteral(extra) }

            val rows = users.select(users.id, users.flags, flagsWithExtra).orderBy(users.id).toList()

            rows shouldHaveSize 5
            rows[0][flagsWithExtra] shouldBeEqualTo 0b0010
            rows[1][flagsWithExtra] shouldBeEqualTo 0b0011
            rows[2][flagsWithExtra] shouldBeEqualTo 0b1010
            rows[3][flagsWithExtra] shouldBeEqualTo 0b1011
            rows[4][flagsWithExtra] shouldBeEqualTo 0b1010
        }
    }

    /**
     * bitwiseXor function
     *
     * ```sql
     * --- Postgres
     * SELECT users.id,
     *        users.flags,
     *        (users.flags # 7)
     *  FROM users
     * ORDER BY users.id ASC;
     *
     * --- MySQL
     * SELECT Users.id,
     *        Users.flags,
     *        (Users.flags ^ 7)
     *  FROM Users
     * ORDER BY Users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bitwiseXor 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val flagsWithExtra = Expression.build { users.flags bitwiseXor 0b111 }
            val rows = users.select(users.id, users.flags, flagsWithExtra).orderBy(users.id).toList()

            rows shouldHaveSize 5
            rows[0][flagsWithExtra] shouldBeEqualTo 0b0111
            rows[1][flagsWithExtra] shouldBeEqualTo 0b0110
            rows[2][flagsWithExtra] shouldBeEqualTo 0b1111
            rows[3][flagsWithExtra] shouldBeEqualTo 0b1110
            rows[4][flagsWithExtra] shouldBeEqualTo 0b1111
        }
    }

    /**
     * bitwiseXor function
     *
     * ```
     * -- Postgres
     * SELECT users.id,
     *        users.flags,
     *        (users.flags # 7)
     *   FROM users
     *  ORDER BY users.id ASC;
     *
     *  -- MySQL
     *  SELECT Users.id,
     *         Users.flags,
     *         (Users.flags ^ 7)
     *    FROM Users
     *   ORDER BY Users.id ASC;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bitwiseXor 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val flagsWithExtra = Expression.build { users.flags bitwiseXor intLiteral(0b111) }
            val rows = users.select(users.id, users.flags, flagsWithExtra).orderBy(users.id).toList()

            rows shouldHaveSize 5
            rows[0][flagsWithExtra] shouldBeEqualTo 0b0111
            rows[1][flagsWithExtra] shouldBeEqualTo 0b0110
            rows[2][flagsWithExtra] shouldBeEqualTo 0b1111
            rows[3][flagsWithExtra] shouldBeEqualTo 0b1110
            rows[4][flagsWithExtra] shouldBeEqualTo 0b1111
        }
    }

    /**
     * hasFlag function
     *
     * ```sql
     * SELECT users.id FROM users
     *  WHERE (users.flags & 1) = 1
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `flag 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val adminFlag = DMLTestData.Users.Flags.IS_ADMIN

            val rows = users
                .select(users.id)
                .where { users.flags hasFlag adminFlag }
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 2
            rows[0][users.id] = "andrey"
            rows[1][users.id] = "sergey"
        }
    }

    /**
     * substring function
     *
     * ```sql
     * SELECT users.id,
     *        SUBSTRING(users."name", 1, 2)
     *   FROM users
     *  ORDER BY users.id ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `substring 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val substring = users.name.substring(1, 2)

            val rows = users
                .select(users.id, substring)
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 5
            rows[0][substring] shouldBeEqualTo "Al"
            rows[1][substring] shouldBeEqualTo "An"
            rows[2][substring] shouldBeEqualTo "Eu"
            rows[3][substring] shouldBeEqualTo "Se"
            rows[4][substring] shouldBeEqualTo "So"
        }
    }

    /**
     * CharLength function
     *
     * ```sql
     * SELECT SUM(CHAR_LENGTH(cities."name")) FROM cities
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CharLength with Sum`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val sumOfLength = CharLength(cities.name).sum()
            val expectedValue = cities.selectAll().sumOf { it[cities.name].length }

            val rows = cities.select(sumOfLength).toList()

            rows shouldHaveSize 1
            rows.single()[sumOfLength] shouldBeEqualTo expectedValue
        }
    }

    /**
     * CharLength function
     *
     * ```sql
     * SELECT CHAR_LENGTH(tester.null_string),
     *        CHAR_LENGTH(tester.empty_string),
     *        CHAR_LENGTH('안녕하세요세계')
     *   FROM tester;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CharLength with edge case Strings`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val nullString = varchar("null_string", 32).nullable()
            val emptyString = varchar("empty_string", 32).nullable()
        }

        withTables(testDB, tester) {
            tester.insert {
                it[nullString] = null
                it[emptyString] = ""
            }

            val helloWorld = "안녕하세요세계" // each character is a 3-byte UTF-8 character

            val nullLength = tester.nullString.charLength()
            val emptyLength = tester.emptyString.charLength()
            val multiByteLength = CharLength(stringLiteral(helloWorld))

            val expectedEmpty = 0
            val expectedMultibyte = helloWorld.length   // 7

            val result = tester.select(nullLength, emptyLength, multiByteLength).single()

            result[nullLength].shouldBeNull()
            result[emptyLength] shouldBeEqualTo expectedEmpty
            result[multiByteLength] shouldBeEqualTo expectedMultibyte   // 7 characters
        }
    }

    /**
     * case function
     *
     * ```sql
     * SELECT users.id,
     *        CASE
     *          WHEN users.id = 'alex' THEN '11'
     *          ELSE '22'
     *        END
     *   FROM users
     *  ORDER BY users.id ASC
     *  LIMIT 2
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select case 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val field = Expression.build {
                case()
                    .When(users.id eq "alex", stringLiteral("11"))
                    .Else(stringLiteral("22"))
            }

            val rows = users.select(users.id, field).orderBy(users.id).limit(2).toList()

            rows shouldHaveSize 2
            rows[0][field] shouldBeEqualTo "11"
            rows[0][users.id] shouldBeEqualTo "alex"
            rows[1][field] shouldBeEqualTo "22"
            rows[1][users.id] shouldBeEqualTo "andrey"
        }
    }

    /**
     * case function
     *
     * ```sql
     * SELECT LOWER(cities."name") FROM cities;
     * SELECT UPPER(cities."name") FROM cities;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `String functions`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->

            val lcase: LowerCase<String> = cities.name.lowerCase()
            cities.select(lcase).any { it[lcase] == "prague" }.shouldBeTrue()

            val ucase: UpperCase<String> = cities.name.upperCase()
            cities.select(ucase).any { it[ucase] == "PRAGUE" }.shouldBeTrue()
        }
    }

    /**
     * locate function
     *
     *
     * ```sql
     * -- Postgres:
     * SELECT POSITION('e' IN cities."name") FROM cities
     *
     * -- MySQL
     * SELECT LOCATE('e',Cities.`name`) FROM Cities
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Locate functions 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val locate = cities.name.locate("e")  // indexOf("e") in the name
            val rows = cities.select(locate).toList()

            rows[0][locate] shouldBeEqualTo 6 // St. Petersburg
            rows[1][locate] shouldBeEqualTo 0 // Munich
            rows[2][locate] shouldBeEqualTo 6 // Prague
        }
    }

    /**
     * locate function
     *
     * ```sql
     * -- Postgres:
     * SELECT POSITION('Peter' IN cities."name") FROM cities;
     *
     * -- MySQL:
     * SELECT LOCATE('Peter',Cities.`name`) FROM Cities;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Locate functions 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val locate = cities.name.locate("Peter")  // indexOf("Peter") in the name
            val rows = cities.select(locate).toList()

            rows[0][locate] shouldBeEqualTo 5 // St. Petersburg
            rows[1][locate] shouldBeEqualTo 0 // Munich
            rows[2][locate] shouldBeEqualTo 0 // Prague
        }
    }

    /**
     * locate function
     *
     * ```sql
     * -- Postgres:
     * SELECT POSITION('p' IN cities."name") FROM cities;
     * -- MySQL:
     * SELECT LOCATE('p',Cities.`name`) FROM Cities
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Locate functions 03`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val isNotCaseSensitiveDialect = currentDialectTest is MysqlDialect || currentDialectTest is SQLServerDialect

            val locate = cities.name.locate("p")  // indexOf("p") in the name
            val rows = cities.select(locate).toList()

            rows[0][locate] shouldBeEqualTo if (isNotCaseSensitiveDialect) 5 else 0 // St. Petersburg
            rows[1][locate] shouldBeEqualTo 0 // Munich
            rows[2][locate] shouldBeEqualTo if (isNotCaseSensitiveDialect) 1 else 0 // Prague
        }
    }

    /**
     * Random function
     *
     * ```sql
     * SELECT RANDOM() FROM cities LIMIT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Random Function 01`(testDB: TestDB) {
        val cities = DMLTestData.Cities

        withTables(testDB, cities) {
            if (cities.selectAll().count() == 0L) {
                cities.insert { it[name] = "city-1" }
            }

            val rand = org.jetbrains.exposed.v1.core.Random()  // BigDecimal 을 반환한다.

            val resultRow = cities
                .select(rand)
                .limit(1)
                .single()

            resultRow[rand].shouldNotBeNull().apply {
                log.debug { "Random=$this" }
            }
        }
    }

    /**
     * Regexp function
     *
     * ```sql
     * -- Postgres:
     * SELECT COUNT(*) FROM users WHERE users.id ~ 'a.+';
     * SELECT COUNT(*) FROM users WHERE users.id ~ 'an.+';
     * SELECT COUNT(*) FROM users WHERE users.id ~ '.*';
     * SELECT COUNT(*) FROM users WHERE users.id ~ '.*y';
     *
     * -- MySQL:
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, 'a.+', 'c');
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, 'an.+', 'c');
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, '.*', 'c');
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, '.*y', 'c');
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `regexp 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            users.selectAll().where { users.id regexp "a.+" }.count().toInt() shouldBeEqualTo 2
            users.selectAll().where { users.id regexp "an.+" }.count().toInt() shouldBeEqualTo 1
            users.selectAll().where { users.id regexp ".*" }.count() shouldBeEqualTo users.selectAll().count()
            users.selectAll().where { users.id regexp ".*y" }.count().toInt() shouldBeEqualTo 2
        }
    }

    /**
     * Regexp function
     *
     * ```sql
     * -- Postgres:
     * SELECT COUNT(*) FROM users WHERE users.id ~ 'a.+';
     * SELECT COUNT(*) FROM users WHERE users.id ~ 'an.+';
     * SELECT COUNT(*) FROM users WHERE users.id ~ '.*';
     * SELECT COUNT(*) FROM users WHERE users.id ~ '.*y';
     *
     * -- MySQL:
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, 'a.+', 'c');
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, 'an.+', 'c');
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, '.*', 'c');
     * SELECT COUNT(*) FROM Users WHERE REGEXP_LIKE(Users.id, '.*y', 'c');
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `regexp 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            users.selectAll().where { users.id.regexp(stringLiteral("a.+")) }.count().toInt() shouldBeEqualTo 2
            users.selectAll().where { users.id.regexp(stringLiteral("an.+")) }.count().toInt() shouldBeEqualTo 1
            users.selectAll()
                .where { users.id.regexp(stringLiteral(".*")) }
                .count() shouldBeEqualTo users.selectAll().count()
            users.selectAll().where { users.id.regexp(stringLiteral(".*y")) }.count().toInt() shouldBeEqualTo 2
        }
    }

    /**
     * concat function
     *
     * ```sql
     * SELECT CONCAT('Foo', 'Bar') FROM cities LIMIT 1;
     * SELECT CONCAT_WS('!','Foo', 'Bar') FROM cities LIMIT 1;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `concat 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val concatField: Concat = SqlExpressionBuilder.concat(stringLiteral("Foo"), stringLiteral("Bar"))
            val result = cities.select(concatField).limit(1).single()
            result[concatField] shouldBeEqualTo "FooBar"

            val concatField2: Concat = SqlExpressionBuilder
                .concat(
                    "!",
                    listOf(
                        stringLiteral("Foo"),
                        stringLiteral("Bar")
                    )
                )

            val result2 = cities
                .select(concatField2)
                .limit(1)
                .single()
            result2[concatField2] shouldBeEqualTo "Foo!Bar"
        }
    }

    /**
     * concat function
     *
     * ```sql
     * SELECT CONCAT(users.id, ' - ', users."name")
     *   FROM users
     *  WHERE users.id = 'andrey';
     *
     * SELECT CONCAT_WS('!',users.id, users."name")
     *   FROM users
     *  WHERE users.id = 'andrey';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `concat 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val concatField: Concat = SqlExpressionBuilder.concat(users.id, stringLiteral(" - "), users.name)
            val result = users.select(concatField).where { users.id eq "andrey" }.single()
            result[concatField] shouldBeEqualTo "andrey - Andrey"

            val concatField2: Concat = SqlExpressionBuilder.concat(
                "!",
                listOf(users.id, users.name)
            )

            val result2 = users
                .select(concatField2)
                .where { users.id eq "andrey" }
                .single()

            result2[concatField2] shouldBeEqualTo "andrey!Andrey"
        }
    }

    /**
     * concat function
     * ```sql
     * SELECT CONCAT(userdata.user_id, ' - ', userdata."comment", ' - ', userdata."value")
     *   FROM userdata
     *  WHERE userdata.user_id = 'sergey';
     *
     * SELECT CONCAT_WS('!',userdata.user_id, userdata."comment", userdata."value")
     *   FROM userdata
     *  WHERE userdata.user_id = 'sergey';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `concat with numbers`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, _, data ->
            val concatField = SqlExpressionBuilder.concat(
                data.userId,
                stringLiteral(" - "),
                data.comment,
                stringLiteral(" - "),
                data.value
            )
            val result = data.select(concatField).where { data.userId eq "sergey" }.single()
            result[concatField] shouldBeEqualTo "sergey - Comment for Sergey - 30"

            val concatField2 = SqlExpressionBuilder.concat(
                "!",
                listOf(
                    data.userId,
                    data.comment,
                    data.value
                )
            )
            val result2 = data.select(concatField2).where { data.userId eq "sergey" }.single()
            result2[concatField2] shouldBeEqualTo "sergey!Comment for Sergey!30"
        }
    }

    /**
     * DB specific string functions
     *
     * ```sql
     * SELECT lower(CITIES."name") FROM CITIES;
     * SELECT upper(CITIES."name") FROM CITIES;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom string functions 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val customLower = cities.name.function("lower")
            cities.select(customLower).any { it[customLower] == "prague" }.shouldBeTrue()

            val customUpper = cities.name.function("upper")
            cities.select(customUpper).any { it[customUpper] == "PRAGUE" }.shouldBeTrue()
        }
    }

    /**
     * DB specific string functions
     *
     * ```sql
     * SELECT REPLACE(cities."name", 'gue', 'foo')
     *   FROM cities
     *  WHERE cities."name" = 'Prague'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom string functions 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            // REPLACE(cities."name", 'gue', 'foo')
            val replace = CustomStringFunction(
                "REPLACE",
                cities.name,
                stringParam("gue"),
                stringParam("foo")
            )

            val result = cities
                .select(replace)
                .where { cities.name eq "Prague" }
                .singleOrNull()

            result?.get(replace) shouldBeEqualTo "Prafoo"
        }
    }

    /**
     * custom integer function
     *
     * ```sql
     * SELECT SQRT(cities.city_id) FROM cities
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom integer function 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val ids = cities.selectAll().map { it[cities.id] }.toList()
            ids shouldBeEqualTo listOf(1, 2, 3)

            val sqrt = DMLTestData.Cities.id.function("SQRT")

            val sqrtIds = cities
                .select(sqrt)
                .map { it[sqrt] }
                .toList()

            sqrtIds shouldBeEqualTo listOf(1, 1, 1)
        }
    }

    /**
     * custom integer function
     *
     * ```sql
     * SELECT POWER(cities.city_id, 2) FROM cities
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom integer function 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val power = CustomLongFunction("POWER", cities.id, intParam(2))
            val ids = cities.select(power).map { it[power] }.toList()
            ids shouldBeEqualTo listOf(1L, 4L, 9L)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `And operator doesn't mutate`(testDB: TestDB) {
        withDb(testDB) {
            val initialOp = Op.build { Cities.name eq "foo" }
            val secondOp = Op.build { Cities.name.isNotNull() }
            (initialOp and secondOp).toString() shouldBeEqualTo "($initialOp) AND ($secondOp)"

            val thirdOp = exists(Cities.selectAll())
            (initialOp and thirdOp).toString() shouldBeEqualTo "($initialOp) AND $thirdOp"

            (initialOp and secondOp and thirdOp).toString() shouldBeEqualTo
                    "($initialOp) AND ($secondOp) AND $thirdOp"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Or operator doesn't mutate`(testDB: TestDB) {
        withDb(testDB) {
            val initialOp = Op.build { Cities.name eq "foo" }
            val secondOp = Op.build { Cities.name.isNotNull() }
            (initialOp or secondOp).toString() shouldBeEqualTo "($initialOp) OR ($secondOp)"

            val thirdOp = exists(Cities.selectAll())
            (initialOp or thirdOp).toString() shouldBeEqualTo "($initialOp) OR $thirdOp"

            (initialOp or secondOp or thirdOp).toString() shouldBeEqualTo
                    "($initialOp) OR ($secondOp) OR $thirdOp"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `And Or combinations`(testDB: TestDB) {
        withDb(testDB) {
            val initialOp = Op.build { Cities.name eq "foo" }
            val secondOp = exists(Cities.selectAll())

            (initialOp or initialOp and initialOp).toString() shouldBeEqualTo
                    "(($initialOp) OR ($initialOp)) AND ($initialOp)"

            (initialOp or initialOp and secondOp).toString() shouldBeEqualTo
                    "(($initialOp) OR ($initialOp)) AND $secondOp"

            (initialOp and initialOp or initialOp).toString() shouldBeEqualTo
                    "(($initialOp) AND ($initialOp)) OR ($initialOp)"

            (initialOp and secondOp or initialOp).toString() shouldBeEqualTo
                    "(($initialOp) AND $secondOp) OR ($initialOp)"

            (initialOp and (initialOp or initialOp)).toString() shouldBeEqualTo
                    "($initialOp) AND (($initialOp) OR ($initialOp))"

            ((initialOp or initialOp) and (initialOp or initialOp)).toString() shouldBeEqualTo
                    "(($initialOp) OR ($initialOp)) AND (($initialOp) OR ($initialOp))"

            (initialOp or initialOp and initialOp or initialOp).toString() shouldBeEqualTo
                    "((($initialOp) OR ($initialOp)) AND ($initialOp)) OR ($initialOp)"

            (initialOp or initialOp or initialOp or initialOp).toString() shouldBeEqualTo
                    "($initialOp) OR ($initialOp) OR ($initialOp) OR ($initialOp)"

            (secondOp or secondOp or secondOp or secondOp).toString() shouldBeEqualTo
                    "$secondOp OR $secondOp OR $secondOp OR $secondOp"

            (initialOp or (initialOp or initialOp) or initialOp).toString() shouldBeEqualTo
                    "($initialOp) OR ($initialOp) OR ($initialOp) OR ($initialOp)"

            (initialOp or (secondOp and secondOp) or initialOp).toString() shouldBeEqualTo
                    "($initialOp) OR ($secondOp AND $secondOp) OR ($initialOp)"

            (initialOp orIfNotNull (null as Expression<Boolean>?)).toString() shouldBeEqualTo "$initialOp"
            (initialOp andIfNotNull (null as Op<Boolean>?)).toString() shouldBeEqualTo "$initialOp"

            (initialOp andIfNotNull (initialOp andIfNotNull (null as Op<Boolean>?))).toString() shouldBeEqualTo
                    "($initialOp) AND ($initialOp)"

            (initialOp andIfNotNull (null as Op<Boolean>?) andIfNotNull initialOp).toString() shouldBeEqualTo
                    "($initialOp) AND ($initialOp)"

            (initialOp andIfNotNull (secondOp andIfNotNull (null as Op<Boolean>?))).toString() shouldBeEqualTo
                    "($initialOp) AND $secondOp"

            (initialOp andIfNotNull (secondOp andIfNotNull (null as Expression<Boolean>?)) orIfNotNull secondOp).toString() shouldBeEqualTo
                    "(($initialOp) AND $secondOp) OR $secondOp"


            (initialOp.andIfNotNull { initialOp }).toString() shouldBeEqualTo "($initialOp) AND ($initialOp)"
        }
    }

    /**
     * [CustomOperator]를 정의하여 사용하기
     *
     * ```sql
     * SELECT userdata.user_id, userdata."comment", userdata."value"
     *   FROM userdata
     *  WHERE (userdata."value" + 15) = 35;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom operator`(testDB: TestDB) {
        // implement a + operator using CustomOperator
        infix fun Expression<*>.plus(operand: Int) =
            CustomOperator("+", IntegerColumnType(), this, intLiteral(operand))

        withCitiesAndUsers(testDB) { _, _, userData ->
            userData
                .selectAll()
                .where { (userData.value plus 15) eq 35 }
                .forEach {
                    it[userData.value] shouldBeEqualTo 20
                    log.debug { "Matched userData. ${it[userData.value]}" }
                }
        }
    }

    /**
     * [SqlExpressionBuilder.coalesce] 함수 사용하기
     *
     * coalesce: 첫번째 인자가 null 이면 두번째 인자를 반환한다.
     *
     * ```sql
     * SELECT users.city_id,
     *        COALESCE(users.city_id, 1000)
     *   FROM users
     * ```
     *
     * ```sql
     * SELECT users.city_id,
     *        COALESCE(users.city_id, NULL, 1000)
     *   FROM users
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `coalesce function`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            val coalesceExpr1 = SqlExpressionBuilder.coalesce(users.cityId, intLiteral(1000))

            users
                .select(users.cityId, coalesceExpr1)
                .forEach {
                    val cityId = it[users.cityId]
                    val actual = it[coalesceExpr1]
                    if (cityId != null) {
                        actual shouldBeEqualTo cityId
                    } else {
                        actual shouldBeEqualTo 1000
                    }
                }

            val coalesceExpr2 = Coalesce(users.cityId, Op.nullOp(), intLiteral(1000))

            users
                .select(users.cityId, coalesceExpr2)
                .forEach {
                    val cityId = it[users.cityId]
                    val actual = it[coalesceExpr2]
                    if (cityId != null) {
                        actual shouldBeEqualTo cityId
                    } else {
                        actual shouldBeEqualTo 1000
                    }
                }
        }
    }

    /**
     * plus operator (`+`) 를 사용하여 문자열을 연결하기
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `concat using plus operator`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            /**
             * ```sql
             * SELECT CONCAT(CONCAT(users.id, ' - '), users."name")
             *   FROM users
             *  WHERE users.id = 'andrey'
             * ```
             */
            val concatField = SqlExpressionBuilder.run { users.id + " - " + users.name }
            val result = users.select(concatField).where { users.id eq "andrey" }.single()
            result[concatField] shouldBeEqualTo "andrey - Andrey"

            /**
             * ```sql
             * SELECT CONCAT(users.id, users."name")
             *   FROM users
             *  WHERE users.id = 'andrey'
             * ```
             */
            val concatField2 = SqlExpressionBuilder.run { users.id + users.name }
            val result2 = users.select(concatField2).where { users.id eq "andrey" }.single()
            result2[concatField2] shouldBeEqualTo "andreyAndrey"

            /**
             * ```sql
             * SELECT CONCAT('Hi ', CONCAT(users."name", '!'))
             *   FROM users
             *  WHERE users.id = 'andrey'
             * ```
             */
            val concatField3 = SqlExpressionBuilder.run { "Hi " plus users.name + "!" }
            val result3 = users.select(concatField3).where { users.id eq "andrey" }.single()
            result3[concatField3] shouldBeEqualTo "Hi Andrey!"
        }
    }
}
