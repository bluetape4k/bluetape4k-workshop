package io.bluetape4k.workshop.exposed.domain.shared.functions

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.asBigDecimal
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.shared.dml.DMLTestData
import io.bluetape4k.workshop.exposed.domain.shared.dml.withCitiesAndUsers
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.CharLength
import org.jetbrains.exposed.sql.Coalesce
import org.jetbrains.exposed.sql.Concat
import org.jetbrains.exposed.sql.CustomLongFunction
import org.jetbrains.exposed.sql.CustomOperator
import org.jetbrains.exposed.sql.CustomStringFunction
import org.jetbrains.exposed.sql.DecimalColumnType
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Sum
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andIfNotNull
import org.jetbrains.exposed.sql.charLength
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.function
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.intParam
import org.jetbrains.exposed.sql.locate
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.orIfNotNull
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.substring
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.upperCase
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class FunctionsTest: AbstractFunctionsTest() {

    companion object: KLogging()

    /**
     * sum function
     * ```sql
     * SELECT SUM(CITIES.CITY_ID) FROM CITIES
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
     * SELECT USERS.ID, SUM((CITIES.CITY_ID + USERDATA."value"))
     *   FROM USERS
     *      INNER JOIN USERDATA ON USERS.ID = USERDATA.USER_ID
     *      INNER JOIN CITIES ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY USERS.ID
     *  ORDER BY USERS.ID ASC
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
     * ```sql
     * SELECT USERS.ID,
     *        SUM(((CITIES.CITY_ID * 100) + (USERDATA."value" / 10))),
     *        (SUM(((CITIES.CITY_ID * 100) + (USERDATA."value" / 10))) / 100),
     *        (SUM(((CITIES.CITY_ID * 100) + (USERDATA."value" / 10))) % 100)
     *   FROM USERS
     *          INNER JOIN USERDATA ON USERS.ID = USERDATA.USER_ID
     *          INNER JOIN CITIES ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY USERS.ID
     *  ORDER BY USERS.ID ASC
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
     * ```sql
     * SELECT USERS.ID,
     *        SUM(((CITIES.CITY_ID * 100.0) + (USERDATA."value" / 10.0))),
     *        (SUM(((CITIES.CITY_ID * 100.0) + (USERDATA."value" / 10.0))) / 100.0),
     *        (SUM(((CITIES.CITY_ID * 100.0) + (USERDATA."value" / 10.0))) % 100.0)
     *   FROM USERS
     *          INNER JOIN USERDATA ON USERS.ID = USERDATA.USER_ID
     *          INNER JOIN CITIES ON CITIES.CITY_ID = USERS.CITY_ID
     *  GROUP BY USERS.ID
     *  ORDER BY USERS.ID ASC
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `rem on numeric PK should work`(testDB: TestDB) {
        val table = object: IntIdTable("test_mod_on_pk") {
            val otherColumn = short("other")
        }

        withTables(testDB, table) {
            repeat(5) {
                table.insert {
                    it[otherColumn] = 4
                }
            }

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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mod on numeric PK should work`(testDB: TestDB) {
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
     * SELECT BITAND(USERS.FLAGS, CAST(1 AS INT)),
     *        BITAND(USERS.FLAGS, CAST(1 AS INT)) = 1
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
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
     * SELECT BITAND(USERS.FLAGS, 1),
     *        BITAND(USERS.FLAGS, 1) = 1
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
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
     * SELECT USERS.ID,
     *        BITOR(USERS.FLAGS, CAST(2 AS INT))
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
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
     * SELECT USERS.ID,
     *        BITOR(USERS.FLAGS, 2)
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
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
     * SELECT USERS.ID,
     *        USERS.FLAGS,
     *        BITXOR(USERS.FLAGS, CAST(7 AS INT))
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
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
     * SELECT USERS.ID,
     *        USERS.FLAGS,
     *        BITXOR(USERS.FLAGS, 7)
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
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
     * SELECT USERS.ID
     *   FROM USERS
     *  WHERE BITAND(USERS.FLAGS, CAST(1 AS INT)) = 1
     *  ORDER BY USERS.ID ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `flag 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val adminFlag = DMLTestData.Users.Flags.IS_ADMIN
            val rows = users.select(users.id)
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
     * SELECT USERS.ID,
     *        SUBSTRING(USERS."name", 1, 2)
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `substring 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val substring = users.name.substring(1, 2)
            val rows = users.select(users.id, substring)
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
     * ```
     * SELECT SUM(CHAR_LENGTH(CITIES."name")) FROM CITIES
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
     * SELECT CHAR_LENGTH(TESTER.NULL_STRING),
     *        CHAR_LENGTH(TESTER.EMPTY_STRING),
     *        CHAR_LENGTH('안녕하세요세계')
     *   FROM TESTER
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
     * SELECT USERS.ID,
     *        CASE
     *          WHEN USERS.ID = 'alex' THEN '11'
     *          ELSE '22'
     *        END
     *   FROM USERS
     *  ORDER BY USERS.ID ASC
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
     * SELECT LOWER(CITIES."name") FROM CITIES
     * SELECT UPPER(CITIES."name") FROM CITIES
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `String functions`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->

            val lcase = cities.name.lowerCase()
            cities.select(lcase).any { it[lcase] == "prague" }.shouldBeTrue()

            val ucase = cities.name.upperCase()
            cities.select(ucase).any { it[ucase] == "PRAGUE" }.shouldBeTrue()
        }
    }

    /**
     * locate function
     *
     * ```sql
     * SELECT LOCATE('e',CITIES."name") FROM CITIES
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
     * SELECT LOCATE('Peter',CITIES."name") FROM CITIES
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
     * SELECT LOCATE('p',CITIES."name") FROM CITIES
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
     * SELECT RANDOM() FROM CITIES LIMIT 1
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

            val rand = Random()
            val resultRow = cities.select(rand).limit(1).single()
            resultRow[rand].shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `regexp 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, 'a.+', 'c')
            users.selectAll().where { users.id regexp "a.+" }.count().toInt() shouldBeEqualTo 2
            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, 'an.+', 'c')
            users.selectAll().where { users.id regexp "an.+" }.count().toInt() shouldBeEqualTo 1
            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, '.*', 'c')
            users.selectAll().where { users.id regexp ".*" }.count() shouldBeEqualTo users.selectAll().count()
            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, '.*y', 'c')
            users.selectAll().where { users.id regexp ".*y" }.count().toInt() shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `regexp 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, 'a.+', 'c')
            users.selectAll().where { users.id.regexp(stringLiteral("a.+")) }.count().toInt() shouldBeEqualTo 2
            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, 'an.+', 'c')
            users.selectAll().where { users.id.regexp(stringLiteral("an.+")) }.count().toInt() shouldBeEqualTo 1
            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, '.*', 'c')
            users.selectAll().where { users.id.regexp(stringLiteral(".*")) }.count() shouldBeEqualTo users.selectAll()
                .count()
            // SELECT COUNT(*) FROM USERS WHERE REGEXP_LIKE(USERS.ID, '.*y', 'c')
            users.selectAll().where { users.id.regexp(stringLiteral(".*y")) }.count().toInt() shouldBeEqualTo 2
        }
    }

    /**
     * concat function
     *
     * ```sql
     * SELECT CONCAT('Foo', 'Bar') FROM CITIES LIMIT 1
     * ```
     * ```sql
     * SELECT CONCAT_WS('!', 'Foo', 'Bar') FROM CITIES LIMIT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `concat 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val concatField: Concat = SqlExpressionBuilder.concat(stringLiteral("Foo"), stringLiteral("Bar"))
            val result = cities.select(concatField).limit(1).single()
            result[concatField] shouldBeEqualTo "FooBar"

            val concatField2: Concat =
                SqlExpressionBuilder.concat("!", listOf(stringLiteral("Foo"), stringLiteral("Bar")))
            val result2 = cities.select(concatField2).limit(1).single()
            result2[concatField2] shouldBeEqualTo "Foo!Bar"
        }
    }

    /**
     * concat function
     *
     * ```sql
     * SELECT CONCAT(USERS.ID, ' - ', USERS."name") FROM USERS WHERE USERS.ID = 'andrey'
     * ```
     *
     * ```
     * SELECT CONCAT_WS('!',USERS.ID, USERS."name") FROM USERS WHERE USERS.ID = 'andrey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `concat 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val concatField: Concat = SqlExpressionBuilder.concat(users.id, stringLiteral(" - "), users.name)
            val result = users.select(concatField).where { users.id eq "andrey" }.single()
            result[concatField] shouldBeEqualTo "andrey - Andrey"

            val concatField2: Concat =
                SqlExpressionBuilder.concat("!", listOf(users.id, users.name))
            val result2 = users.select(concatField2).where { users.id eq "andrey" }.single()
            result2[concatField2] shouldBeEqualTo "andrey!Andrey"
        }
    }

    /**
     * concat function
     * ```sql
     * SELECT CONCAT(USERDATA.USER_ID, ' - ', USERDATA.COMMENT, ' - ', USERDATA."value")
     *   FROM USERDATA
     *  WHERE USERDATA.USER_ID = 'sergey'
     * ```
     *
     * ```sql
     * SELECT CONCAT_WS('!',USERDATA.USER_ID, USERDATA.COMMENT, USERDATA."value")
     *   FROM USERDATA
     *  WHERE USERDATA.USER_ID = 'sergey'
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
     * custom string functions
     *
     * ```sql
     * SELECT lower(CITIES."name") FROM CITIES
     * ```
     * ```sql
     * SELECT upper(CITIES."name") FROM CITIES
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
     * custom string functions
     * ```sql
     * SELECT REPLACE(CITIES."name", 'gue', 'foo')
     *   FROM CITIES
     *  WHERE CITIES."name" = 'Prague'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom string functions 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val replace = CustomStringFunction(
                "REPLACE",
                cities.name,
                stringParam("gue"),
                stringParam("foo")
            )
            val result = cities.select(replace).where { cities.name eq "Prague" }.singleOrNull()
            result?.get(replace) shouldBeEqualTo "Prafoo"
        }
    }

    /**
     * custom integer function
     *
     * ```sql
     * SELECT SQRT(CITIES.CITY_ID) FROM CITIES
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom integer function 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val ids = cities.selectAll().map { it[cities.id] }.toList()
            ids shouldBeEqualTo listOf(1, 2, 3)

            val sqrt = DMLTestData.Cities.id.function("SQRT")
            val sqrtIds = cities.select(sqrt).map { it[sqrt] }.toList()
            sqrtIds shouldBeEqualTo listOf(1, 1, 1)
        }
    }

    /**
     * custom integer function
     *
     * ```sql
     * SELECT POWER(CITIES.CITY_ID, 2) FROM CITIES
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
            val initialOp = Op.build { DMLTestData.Cities.name eq "foo" }
            val secondOp = Op.build { DMLTestData.Cities.name.isNotNull() }
            (initialOp and secondOp).toString() shouldBeEqualTo "($initialOp) AND ($secondOp)"

            val thirdOp = exists(DMLTestData.Cities.selectAll())
            (initialOp and thirdOp).toString() shouldBeEqualTo "($initialOp) AND $thirdOp"

            (initialOp and secondOp and thirdOp).toString() shouldBeEqualTo
                    "($initialOp) AND ($secondOp) AND $thirdOp"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Or operator doesn't mutate`(testDB: TestDB) {
        withDb(testDB) {
            val initialOp = Op.build { DMLTestData.Cities.name eq "foo" }
            val secondOp = Op.build { DMLTestData.Cities.name.isNotNull() }
            (initialOp or secondOp).toString() shouldBeEqualTo "($initialOp) OR ($secondOp)"

            val thirdOp = exists(DMLTestData.Cities.selectAll())
            (initialOp or thirdOp).toString() shouldBeEqualTo "($initialOp) OR $thirdOp"

            (initialOp or secondOp or thirdOp).toString() shouldBeEqualTo
                    "($initialOp) OR ($secondOp) OR $thirdOp"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `And Or combinations`(testDB: TestDB) {
        withDb(testDB) {
            val initialOp = Op.build { DMLTestData.Cities.name eq "foo" }
            val secondOp = exists(DMLTestData.Cities.selectAll())

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
     * custom operator
     *
     * ```sql
     * SELECT USERDATA.USER_ID, USERDATA.COMMENT, USERDATA."value"
     *   FROM USERDATA WHERE (USERDATA."value" + 15) = 35
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `coalesce function`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            val coalesceExpr1 = SqlExpressionBuilder.coalesce(users.cityId, intLiteral(1000))

            /**
             * ```sql
             * SELECT USERS.CITY_ID, COALESCE(USERS.CITY_ID, 1000) FROM USERS
             * ```
             */
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

            /**
             * ```sql
             * SELECT USERS.CITY_ID, COALESCE(USERS.CITY_ID, NULL, 1000) FROM USERS
             * ```
             */
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `concat using plus operator`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            /**
             * ```sql
             * SELECT CONCAT(CONCAT(USERS.ID, ' - '), USERS."name") FROM USERS WHERE USERS.ID = 'andrey'
             * ```
             */
            val concatField = SqlExpressionBuilder.run { users.id + " - " + users.name }
            val result = users.select(concatField).where { users.id eq "andrey" }.single()
            result[concatField] shouldBeEqualTo "andrey - Andrey"

            /**
             * ```sql
             * SELECT CONCAT(USERS.ID, USERS."name") FROM USERS WHERE USERS.ID = 'andrey'
             * ```
             */
            val concatField2 = SqlExpressionBuilder.run { users.id + users.name }
            val result2 = users.select(concatField2).where { users.id eq "andrey" }.single()
            result2[concatField2] shouldBeEqualTo "andreyAndrey"

            /**
             * ```sql
             * SELECT CONCAT('Hi ', CONCAT(USERS."name", '!')) FROM USERS WHERE USERS.ID = 'andrey'
             * ```
             */
            val concatField3 = SqlExpressionBuilder.run { "Hi " plus users.name + "!" }
            val result3 = users.select(concatField3).where { users.id eq "andrey" }.single()
            result3[concatField3] shouldBeEqualTo "Hi Andrey!"
        }
    }
}
