package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toBigDecimal
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Board
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Boards
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Categories
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Category
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Post
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Posts
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.allFrom
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.anyFrom
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.compoundOr
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * 다양한 SELECT 문을 테스트합니다.
 */
class SelectTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * simple selectAll
     *
     * Postgres:
     * ```sql
     * SELECT users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select all with where clause`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            users.selectAll()
                .where { users.id eq "andrey" }
                .forEach {
                    val userId = it[users.id]
                    val userName = it[users.name]
                    log.debug { "userId: $userId, userName: $userName" }

                    when (userId) {
                        "andrey" -> userName shouldBeEqualTo "Andrey"
                        else -> error("Unexpected user id: $userId")
                    }
                }
        }
    }

    /**
     * WHERE clause - multiple conditions
     *
     * Postgres:
     * ```sql
     * SELECT users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags
     *   FROM users
     *  WHERE (users.id = 'andrey')
     *    AND (users."name" IS NOT NULL)
     *  ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select all with where clause - multiple conditions - and`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            users.selectAll()
                .where { users.id.eq("andrey") and users.name.isNotNull() }   // andWhere 를 써도 된다.
                .forEach {
                    val userId = it[users.id]
                    val userName = it[users.name]
                    log.debug { "userId: $userId, userName: $userName" }

                    when (userId) {
                        "andrey" -> userName shouldBeEqualTo "Andrey"
                        else -> error("Unexpected user id: $userId")
                    }
                }
        }
    }

    /**
     * Postgres:
     * ```sql
     * SELECT users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags
     *   FROM users
     *  WHERE (users.id = 'andrey')
     *     OR (users."name" = 'Andrey')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select all with where clause - multiple conditions - or`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            users.selectAll()
                .where { users.id.eq("andrey") or users.name.eq("Andrey") } // orWhere 를 써도 된다.
                .forEach {
                    val userId = it[users.id]
                    val userName = it[users.name]
                    log.debug { "userId: $userId, userName: $userName" }

                    when (userId) {
                        "andrey" -> userName shouldBeEqualTo "Andrey"
                        else -> error("Unexpected user id: $userId")
                    }
                }
        }
    }

    /**
     * ```sql
     * SELECT users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags
     *   FROM users
     *  WHERE (users.id <> 'andrey')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select not equal`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            users.selectAll()
                .where { users.id.neq("andrey") }
                .forEach {
                    val userId = it[users.id]
                    userId shouldNotBeEqualTo "andrey"
                }
        }
    }

    /**
     * [SizedIterable] 을 사용한 SELECT 문
     *
     * ```sql
     * SELECT cities.city_id, cities."name" FROM cities
     * ```
     * ```sql
     * SELECT cities.city_id, cities."name" FROM cities WHERE cities."name" = 'Qwertt'
     * ```
     *
     * ```sql
     * SELECT COUNT(*) FROM cities WHERE cities."name" = 'Qwertt'
     * ```
     * ```
     * SELECT COUNT(*) FROM cities
     * ```
     * ```sql
     * SELECT COUNT(*) FROM users WHERE users.city_id IS NULL
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select sized iterable`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, users, _ ->
            cities.selectAll().shouldNotBeEmpty()

            /**
             * ```sql
             * SELECT cities.city_id, cities."name" FROM cities WHERE cities."name" = 'Qwertt'
             * ```
             */
            cities.selectAll().where { cities.name eq "Qwertt" }.shouldBeEmpty()

            /**
             * ```sql
             * SELECT COUNT(*) FROM cities WHERE cities."name" = 'Qwertt'
             * ```
             */
            cities.selectAll().where { cities.name eq "Qwertt" }.count() shouldBeEqualTo 0L

            cities.selectAll().count() shouldBeEqualTo 3L

            val cityId: Int? = null
            /**
             * ```sql
             * SELECT COUNT(*) FROM users WHERE users.city_id IS NULL
             * ```
             */
            users.selectAll().where { users.cityId eq cityId }.count() shouldBeEqualTo 2L   // isNull() 을 사용해도 된다.
        }
    }

    /**
     * `inList` 를 사용한 조회
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with single expression 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            /**
             * ```sql
             * SELECT users.id, users."name", users.city_id, users.flags
             *   FROM users
             *  WHERE users.id IN ('andrey', 'alex')
             *  ORDER BY users."name" ASC
             * ```
             */
            val r1 = users
                .selectAll()
                .where { users.id inList listOf("andrey", "alex") }
                .orderBy(users.name)
                .toList()

            r1.size shouldBeEqualTo 2
            r1[0][users.name] shouldBeEqualTo "Alex"
            r1[1][users.name] shouldBeEqualTo "Andrey"

            /**
             * ```sql
             * SELECT users.id, users."name", users.city_id, users.flags
             *   FROM users
             *  WHERE users.id NOT IN ('ABC', 'DEF')
             * ```
             */
            val r2 = users.selectAll()
                .where { users.id notInList listOf("ABC", "DEF") }
                .toList()

            users.selectAll().count() shouldBeEqualTo r2.size.toLong()
        }
    }

    /**
     * `inList` 를 사용한 조회
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with single expression 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val cityIds: List<Int> = cities.selectAll().map { it[cities.id] }.take(2)

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM cities
             *  WHERE cities.city_id IN (1, 2)
             * ```
             */
            val r = cities.selectAll()
                .where { cities.id inList cityIds }

            r.count() shouldBeEqualTo 2L
        }
    }

    /**
     * `inList` 에 Pair 형식으로 사용하기
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with pair expression 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            /**
             * `inList` 의 인자로 Pair 를 전달하면, `IN` 조건이 생성됩니다.
             *
             * ```sql
             * SELECT users.id, users."name", users.city_id, users.flags
             *   FROM users
             *  WHERE (users.id, users."name") IN (('andrey', 'Andrey'), ('alex', 'Alex'))
             *  ORDER BY users."name" ASC
             * ```
             */
            val rows = users
                .selectAll()
                .where {
                    (users.id to users.name) inList listOf("andrey" to "Andrey", "alex" to "Alex")
                }
                .orderBy(users.name)
                .toList()

            rows shouldHaveSize 2
            rows[0][users.name] shouldBeEqualTo "Alex"
            rows[1][users.name] shouldBeEqualTo "Andrey"
        }
    }

    /**
     * `inList` 에 Pair 형식으로 사용하기
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE (users.id, users."name") = ('andrey', 'Andrey')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with pair expression 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id to users.name inList listOf("andrey" to "Andrey")
                }
                .toList()

            rows shouldHaveSize 1
            rows[0][users.name] shouldBeEqualTo "Andrey"
        }
    }

    /**
     * `inList` 의 인자로 emptyList 를 전달하면, `FALSE` 조건이 생성됩니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE FALSE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with pair expression and emptyList`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->

            val rows = users
                .selectAll()
                .where {
                    users.id to users.name inList emptyList()
                }
                .toList()

            rows.shouldBeEmpty()
        }
    }

    /**
     * ### `notInList` 에 emptyList 를 전달하면, `TRUE` 조건이 생성됩니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE TRUE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `notInList with pair expression and emptyList`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id to users.name notInList emptyList()
                }
                .toList()

            rows.size shouldBeEqualTo users.selectAll().count().toInt()
        }
    }

    /**
     *
     * ### notInList with triple expressions
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE (users.id, users."name", users.city_id) NOT IN (('andrey', 'Andrey', 1), ('sergey', 'Sergey', 2))
     * ```
     *
     * ### inList with triple expressions
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE (users.id, users."name", users.city_id) IN (('andrey', 'Andrey', 1), ('sergey', 'Sergey', 2))
     * ```
     */
    // @Disabled("제대로 작동하지 않는다. notInList 시에는 3개가 조회되어야 하는데, eugene 만 조회된다.")
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with triple expressions`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_POSTGRES }

        withCitiesAndUsers(testDB) { _, users, _ ->
            log.debug { "users count=${users.selectAll().count()}" }

            val userExpr = Triple(users.id, users.name, users.cityId)

            val rows1 = users.selectAll()
                .where {
                    userExpr notInList listOf(
                        Triple("andrey", "Andrey", 1),
                        Triple("sergey", "Sergey", 2),
                    )
                }
                .toList()

            rows1.forEach {
                log.debug { "row1: user id=${it[users.id]}, name=${it[users.name]}, cityId=${it[users.cityId]}, flags=${it[users.flags]}" }
            }

            rows1.size shouldBeEqualTo users.selectAll().count().toInt() - 2

            val rows2 = users.selectAll()
                .where {
                    userExpr inList listOf(
                        Triple("andrey", "Andrey", 1),
                        Triple("sergey", "Sergey", 2),
                    )
                }
                .toList()
            rows2.forEach {
                log.debug { "row1: user id=${it[users.id]}, name=${it[users.name]}, cityId=${it[users.cityId]}, flags=${it[users.flags]}" }
            }

            rows2 shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with Multiple columns`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS tester (
         *      num_1 INT NOT NULL,
         *      num_2 DOUBLE PRECISION NOT NULL,
         *      num_3 VARCHAR(8) NOT NULL,
         *      num_4 BIGINT NOT NULL
         * )
         * ```
         */
        val tester = object: Table("tester") {
            val num1 = integer("num_1")
            val num2 = double("num_2")
            val num3 = varchar("num_3", 8)
            val num4 = long("num_4")
        }

        fun Int.toColumnValue(index: Int) = when (index) {
            1 -> toDouble()
            2 -> toString()
            3 -> toLong()
            else -> this
        }

        withTables(testDB, tester) {
            repeat(3) { n ->
                tester.insert {
                    it[num1] = n
                    it[num2] = n.toDouble()
                    it[num3] = n.toString()
                    it[num4] = n.toLong()
                }
            }

            val expected = tester.selectAll().count().toInt()   // 3

            // (0, 0.0, '0', 0), (1, 1.0, '1', 1), (2, 2.0, '2', 2)
            val allSameNumbers = List(3) { n ->
                List(4) { n.toColumnValue(it) }
            }

            /**
             * ```sql
             * SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
             *   FROM tester
             *  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4)
             *     IN ((0, 0.0, '0', 0), (1, 1.0, '1', 1), (2, 2.0, '2', 2))
             * ```
             */
            val result1 = tester.selectAll()
                .where { tester.columns inList allSameNumbers }
                .toList()
            result1.size shouldBeEqualTo expected

            /**
             * ```sql
             * SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
             *   FROM tester
             *  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4) = (0, 0.0, '0', 0)
             * ```
             */
            val result2 = tester.selectAll()
                .where { tester.columns inList listOf(allSameNumbers.first()) }
                .toList()
            result2.size shouldBeEqualTo 1


            // (0, 1.0, '2', 3), (1, 2.0, '3', 4), (2, 3.0, '4', 5)
            val allDifferentNumbers = List(3) { n ->
                List(4) { (n + it).toColumnValue(it) }
            }

            /**
             * ```sql
             * SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
             *   FROM tester
             *  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4)
             *        NOT IN ((0, 1.0, '2', 3), (1, 2.0, '3', 4), (2, 3.0, '4', 5))
             * ```
             */
            val result3 = tester.selectAll()
                .where { tester.columns notInList allDifferentNumbers }
                .toList()
            result3.size shouldBeEqualTo expected

            /**
             * ```sql
             * SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
             *   FROM tester
             *  WHERE TRUE
             * ```
             */
            val result4 = tester.selectAll()
                .where { tester.columns notInList emptyList() }
                .toList()
            result4.size shouldBeEqualTo expected

            /**
             * ```sql
             * SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
             *   FROM tester
             *  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4)
             *        NOT IN ((0, 0.0, '0', 0), (1, 1.0, '1', 1), (2, 2.0, '2', 2))
             * ```
             */
            val result5 = tester.selectAll()
                .where { tester.columns notInList allSameNumbers }
                .toList()
            result5.shouldBeEmpty()

            /**
             * ```sql
             * SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
             *   FROM tester
             *  WHERE FALSE
             * ```
             */
            val result6 = tester.selectAll()
                .where { tester.columns inList emptyList() }
                .toList()
            result6.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with entityID columns`(testDB: TestDB) {
        withTables(testDB, Posts, Boards, Categories) {
            val board1 = Board.new {
                name = "board1"
            }
            val post1 = Post.new {
                board = board1
            }
            Post.new {
                category = Category.new { title = "category1" }
            }

            /**
             * ```sql
             * SELECT posts.id, posts.board, posts.parent, posts.category, posts."optCategory"
             *   FROM posts
             *  WHERE posts.board = 1
             * ```
             */
            val result1 = Posts
                .selectAll()
                .where {
                    Posts.boardId inList listOf(board1.id)   // 항목이 한개라면 `eq` 로 대체 가능
                }
                .singleOrNull()
                ?.get(Posts.id)

            result1 shouldBeEqualTo post1.id

            /**
             * `inList` with `EntityID` columns
             *
             * ```sql
             * SELECT board.id, board."name"
             *   FROM board
             *  WHERE board.id IN (1, 2, 3, 4, 5)
             * ```
             */
            val result2 = Board
                .find {
                    Boards.id inList listOf(1, 2, 3, 4, 5)
                }
                .singleOrNull()
            result2 shouldBeEqualTo board1

            /**
             * `notInList` with entityID columns
             *
             * ```sql
             * SELECT board.id, board."name"
             *   FROM board
             *  WHERE board.id  NOT IN (1, 2, 3, 4, 5)
             * ```
             */
            val result3 = Board
                .find {
                    Boards.id notInList listOf(1, 2, 3, 4, 5)
                }
                .singleOrNull()
            result3.shouldBeNull()
        }
    }

    /**
     * ### In with SubQuery
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM cities
     *  WHERE cities.city_id IN (SELECT cities.city_id
     *                             FROM cities
     *                            WHERE cities.city_id = 2)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inSubQuery 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val r: Query = cities
                .selectAll()
                .where {
                    cities.id inSubQuery cities.select(cities.id).where { cities.id eq 2 }
                }

            r.count() shouldBeEqualTo 1L
        }
    }

    /**
     * ### `notInSubQuery` with SubQuery
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM cities
     *  WHERE cities.city_id NOT IN (SELECT cities.city_id FROM cities)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `notInSubQuery with NoData`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val r = cities.selectAll()
                .where { cities.id notInSubQuery cities.select(cities.id) }

            r.count() shouldBeEqualTo 0L
        }
    }

    private val supportingInAnyAllFromTables = TestDB.ALL_POSTGRES + TestDB.H2_PSQL + TestDB.MYSQL_V8

    /**
     * ### `inTable` example
     *
     * SomeAmount 테이블의 amount 컬럼의 값과 같은 sales 테이블의 amount 컬럼의 갯수를 조회합니다.
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM sales
     *  WHERE sales.amount IN (TABLE SomeAmounts)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inTable example`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingInAnyAllFromTables }

        withSalesAndSomeAmounts(testDB) { _, sales, someAmounts ->
            val rows = sales
                .selectAll()
                .where {
                    sales.amount inTable someAmounts
                }
            rows.count() shouldBeEqualTo 2L
        }
    }

    /**
     * ### `notInTable` example
     *
     * ```sql
     * -- Postgres
     * SELECT COUNT(*)
     *   FROM sales
     *  WHERE sales.amount NOT IN (TABLE SomeAmounts)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `notInTable example`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingInAnyAllFromTables }

        withSalesAndSomeAmounts(testDB) { _, sales, someAmounts ->
            val rows = sales
                .selectAll()
                .where {
                    sales.amount notInTable someAmounts
                }
            rows.count() shouldBeEqualTo 5L
        }
    }

    private val supportingAnyAndAllFromSubQueries = TestDB.ALL
    private val supportingAnyAndAllFromArrays = TestDB.ALL_POSTGRES + TestDB.ALL_H2

    /**
     * ### Eq [anyFrom] with SubQuery
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM cities
     *  WHERE cities.city_id = ANY (SELECT cities.city_id
     *                                FROM cities
     *                               WHERE cities.city_id = 2)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `eq AnyFrom SubQuery`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val subquery: Query = cities
                .select(cities.id)
                .where { cities.id eq 2 }

            val rows = cities
                .selectAll()
                .where {
                    cities.id eq anyFrom(subquery)
                }

            rows.count() shouldBeEqualTo 1L
        }
    }

    /**
     * ### `neq` and [anyFrom] with SubQuery
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM cities
     *  WHERE cities.city_id <> ANY (SELECT cities.city_id
     *                                 FROM cities
     *                                WHERE cities.city_id = 2)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `neq AnyFrom SubQuery`(dialect: TestDB) {
        withCitiesAndUsers(dialect) { cities, _, _ ->
            val subquery: Query = cities
                .select(cities.id)
                .where { cities.id eq 2 }

            val rows = cities
                .selectAll()
                .where {
                    cities.id neq anyFrom(subquery)
                }

            rows.count() shouldBeEqualTo 2L
        }
    }

    /**
     * ### eq [anyFrom] with Array
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = ANY (ARRAY['andrey','alex'])
     *  ORDER BY users."name" ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `eq AnyFrom Array`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users
                .selectAll()
                .where {
                    users.id eq anyFrom(arrayOf("andrey", "alex"))
                }
                .orderBy(users.name)
                .toList()

            rows shouldHaveSize 2
            rows[0][users.name] shouldBeEqualTo "Alex"
            rows[1][users.name] shouldBeEqualTo "Andrey"
        }
    }

    /**
     * ### eq [anyFrom] with List
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = ANY (ARRAY['andrey','alex'])
     *  ORDER BY users."name" ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `eq AnyFrom List`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users
                .selectAll()
                .where {
                    users.id eq anyFrom(listOf("andrey", "alex"))
                }
                .orderBy(users.name)
                .toList()

            rows shouldHaveSize 2
            rows[0][users.name] shouldBeEqualTo "Alex"
            rows[1][users.name] shouldBeEqualTo "Andrey"
        }
    }

    /**
     * ### Neq AnyFrom Array
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM users
     *  WHERE users.id <> ANY (ARRAY['andrey'])
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `neq AnyFrom Array`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users
                .selectAll()
                .where {
                    users.id neq anyFrom(arrayOf("andrey"))
                }
                .orderBy(users.name)

            rows.count() shouldBeEqualTo 4L
        }
    }

    /**
     * ### Neq AnyFrom List
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM users
     *  WHERE users.id <> ANY (ARRAY['andrey'])
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `neq AnyFrom List`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users
                .selectAll()
                .where {
                    users.id neq anyFrom(listOf("andrey"))
                }
                .orderBy(users.name)

            rows.count() shouldBeEqualTo 4L
        }
    }

    /**
     * ### eq AnyFrom of Empty Array
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = ANY (ARRAY[])
     *  ORDER BY users."name" ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `eq AnyFrom empty array`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id eq anyFrom(emptyArray())
                }
                .orderBy(users.name)

            rows.shouldBeEmpty()
        }
    }

    /**
     * ### eq AnyFrom of Empty List
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id = ANY (ARRAY[])
     *  ORDER BY users."name" ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `eq AnyFrom empty list`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id eq anyFrom(emptyList())
                }
                .orderBy(users.name)

            rows.shouldBeEmpty()
        }
    }

    /**
     * ### Neq AnyFrom Empty Array
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id <> ANY (ARRAY[])
     *  ORDER BY users."name" ASC
     *  ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `neq AnyFrom empty Array`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id neq anyFrom(emptyArray())
                }
                .orderBy(users.name)

            rows.shouldBeEmpty()
        }
    }

    /**
     * ### Neq AnyFrom Empty List
     * 참고: [anyFrom]은 Postgres, H2 만 지원합니다.
     *
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags
     *   FROM users
     *  WHERE users.id <> ANY (ARRAY[])
     *  ORDER BY users."name" ASC
     *  ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `neq AnyFrom empty List`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withCitiesAndUsers(testDB) { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id neq anyFrom(emptyList())
                }
                .orderBy(users.name)

            rows.shouldBeEmpty()
        }
    }

    /**
     * ### Greater Eq AnyFrom Array
     *
     * Postgres:
     * ```sql
     * SELECT SALES."year", SALES."month", SALES.PRODUCT, SALES.AMOUNT
     *   FROM SALES
     *  WHERE SALES.AMOUNT >= ANY (ARRAY [100,1000])
     *  ORDER BY SALES.AMOUNT ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `greater eq AnyFrom Array`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withSales(testDB) { _, sales ->
            val amounts = arrayOf(100, 1000).map { it.toBigDecimal() }.toTypedArray()

            val rows = sales.selectAll()
                .where {
                    sales.amount greaterEq anyFrom(amounts)
                }
                .orderBy(sales.amount)
                .map { it[sales.product] }

            rows.subList(0, 3).forEach { it shouldBeEqualTo "tea" }
            rows.subList(3, 6).forEach { it shouldBeEqualTo "coffee" }
        }
    }

    /**
     * ### Greater Eq AnyFrom List
     *
     * Postgres:
     * ```sql
     * SELECT SALES."year", SALES."month", SALES.PRODUCT, SALES.AMOUNT
     *   FROM SALES
     *  WHERE SALES.AMOUNT >= ANY (ARRAY [100.0,1000.0])
     *  ORDER BY SALES.AMOUNT ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `greater eq AnyFrom List`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withSales(testDB) { _, sales ->
            val amounts = listOf(100.0, 1000.0).map { it.toBigDecimal() }

            val rows = sales
                .selectAll()
                .where {
                    sales.amount greaterEq anyFrom(amounts)
                }
                .orderBy(sales.amount)
                .map { it[sales.product] }

            rows.subList(0, 3).forEach { it shouldBeEqualTo "tea" }
            rows.subList(3, 6).forEach { it shouldBeEqualTo "coffee" }
        }
    }

    /**
     * eq [anyFrom] with Table
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM sales
     *  WHERE sales.amount = ANY (TABLE SomeAmounts)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Eq AnyFrom Table`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingInAnyAllFromTables }

        withSalesAndSomeAmounts(testDB) { _, sales, someAmounts ->
            val rows = sales
                .selectAll()
                .where {
                    sales.amount eq anyFrom(someAmounts)
                }

            rows.count() shouldBeEqualTo 2L        // 650.70, 1500.25
        }
    }

    /**
     * ### Neq AnyFrom of Table
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM sales
     *  WHERE sales.amount <> ANY (TABLE SomeAmounts)
     * ```
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Neq AnyFrom Table`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingInAnyAllFromTables }

        withSalesAndSomeAmounts(testDB) { _, sales, someAmounts ->
            val rows = sales
                .selectAll()
                .where {
                    sales.amount neq anyFrom(someAmounts)
                }
            rows.count() shouldBeEqualTo 7L    // except 650.70, 1500.25 이어야 하는데 ...
        }
    }

    /**
     * ### `greaterEq` [allFrom] of SubQuery
     *
     * Subquery 에서 max() 를 사용하는 게 더 낫지 않나?
     *
     * Postgres:
     * ```sql
     * SELECT sales."year", sales."month", sales.product, sales.amount
     *   FROM sales
     *  WHERE sales.amount >= ALL (SELECT sales.amount
     *                               FROM sales
     *                              WHERE sales.product = 'tea')
     *  ORDER BY sales.amount ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Greater Eq AllFrom SubQuery`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromSubQueries }
        // MySQL 5.x 에서는 지원되지 않습니다.
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V5 }

        withSales(testDB) { _, sales ->
            val subquery = sales.select(sales.amount).where { sales.product eq "tea" }

            val rows = sales
                .selectAll()
                .where {
                    sales.amount greaterEq allFrom(subquery)
                }
                .orderBy(sales.amount)
                .map { it[sales.product] }

            rows shouldHaveSize 4
            rows.first() shouldBeEqualTo "tea"
            rows.drop(1).forEach { it shouldBeEqualTo "coffee" }
        }
    }

    /**
     * ### `greaterEq`  [allFrom] with Array
     * array 의 max() 를 사용하는 게 더 낫지 않나?
     *
     * Postgres:
     * ```sql
     * SELECT sales."year", sales."month", sales.product, sales.amount
     *   FROM sales
     *  WHERE sales.amount >= ALL (ARRAY[100.0,1000.0])
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Greater Eq AllFrom Array`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withSales(testDB) { _, sales ->
            val amounts = arrayOf(100.0, 1000.0).map { it.toBigDecimal() }.toTypedArray()

            val rows = sales
                .selectAll()
                .where {
                    sales.amount greaterEq allFrom(amounts)
                }
                .toList()

            rows shouldHaveSize 3
            rows.forEach { it[sales.product] shouldBeEqualTo "coffee" }
        }
    }

    /**
     * ### `greaterEq` with [allFrom] of List
     *
     * list 의 max() 를 사용하는 게 더 낫지 않나?
     *
     * ```sql
     * SELECT sales."year", sales."month", sales.product, sales.amount
     *   FROM sales
     *  WHERE sales.amount >= ALL (ARRAY[100.0,1000.0])
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Greater Eq AllFrom List`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingAnyAndAllFromArrays }

        withSales(testDB) { _, sales ->
            val amounts = arrayOf(100.0, 1000.0).map { it.toBigDecimal() }

            val rows = sales
                .selectAll()
                .where {
                    sales.amount greaterEq allFrom(amounts)
                }
                .toList()

            rows shouldHaveSize 3
            rows.forEach { it[sales.product] shouldBeEqualTo "coffee" }
        }

    }

    /**
     * ### `greaterEq` with [allFrom] of Table
     * table 대신 subquery의 max() 를 사용하는 게 더 낫지 않나?
     *
     * Postgres:
     * ```sql
     * SELECT sales."year", sales."month", sales.product, sales.amount
     *   FROM sales
     *  WHERE sales.amount >= ALL (TABLE SomeAmounts)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Greater Eq AllFrom Table`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportingInAnyAllFromTables }

        withSalesAndSomeAmounts(testDB) { _, sales, someAmounts ->
            val rows = sales
                .selectAll()
                .where { sales.amount greaterEq allFrom(someAmounts) }
                .toList()

            rows shouldHaveSize 3
            rows.forEach { it[sales.product] shouldBeEqualTo "coffee" }
        }
    }

    /**
     * ### SELECT DISTINCT 예제 (`withDistinct`, `withDistinctOn`)
     *
     * Postgres:
     * ```sql
     * SELECT COUNT(*)
     *   FROM (SELECT DISTINCT cities.city_id Cities_city_id,
     *                         cities."name" Cities_name
     *           FROM cities
     *   ) subquery
     * ```
     * ```sql
     * SELECT COUNT(*)
     *   FROM (SELECT DISTINCT cities."name" Cities_name
     *           FROM cities
     *   ) subquery
     * ```
     * ```sql
     * SELECT COUNT(*)
     *   FROM (SELECT DISTINCT ON (cities."name")
     *                cities.city_id Cities_city_id,
     *                cities."name" Cities_name
     *           FROM cities
     *   ) subquery
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select distinct`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_MYSQL_LIKE }

        val tbl = DMLTestData.Cities
        withTables(testDB, tbl) {
            tbl.insert { it[tbl.name] = "test" }
            tbl.insert { it[tbl.name] = "test" }

            tbl.selectAll().count() shouldBeEqualTo 2L

            tbl.selectAll()
                .withDistinct()
                .count() shouldBeEqualTo 2L

            tbl.select(tbl.name)
                .withDistinct()
                .count() shouldBeEqualTo 1L

            tbl.selectAll()
                .withDistinctOn(tbl.name)
                .count() shouldBeEqualTo 1L
        }
    }

    /**
     * ### Compound Operations
     *
     * * [compoundOr] 함수는 여러 개의 [Op]를 OR 연산자로 결합합니다.
     * * [compoundAnd] 함수는 여러 개의 [Op]를 AND 연산자로 결합합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `compound operations`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val allUsers = setOf(
                "Andrey",
                "Sergey",
                "Eugene",
                "Alex",
                "Something"
            )

            /**
             * [compoundOr] 함수는 여러 개의 [Op]를 OR 연산자로 결합합니다.
             *
             * ```sql
             * SELECT users.id, users."name", users.city_id, users.flags
             *   FROM users
             *  WHERE (users."name" = 'Andrey')
             *     OR (users."name" = 'Sergey')
             *     OR (users."name" = 'Eugene')
             *     OR (users."name" = 'Alex')
             *     OR (users."name" = 'Something')
             * ```
             */
            val orOp = allUsers.map { Op.build { users.name eq it } }.compoundOr()
            val userNameOr = users
                .selectAll()
                .where(orOp)
                .map { it[users.name] }
                .toSet()
            userNameOr shouldBeEqualTo allUsers

            /**
             * [compoundAnd] 함수는 여러 개의 [Op]를 AND 연산자로 결합합니다.
             *
             * ```sql
             * SELECT COUNT(*)
             *   FROM users
             *  WHERE (users."name" = 'Andrey')
             *    AND (users."name" = 'Sergey')
             *    AND (users."name" = 'Eugene')
             *    AND (users."name" = 'Alex')
             *    AND (users."name" = 'Something')
             * ```
             */
            val andOp = allUsers.map { Op.build { users.name eq it } }.compoundAnd()
            users
                .selectAll()
                .where(andOp)
                .count() shouldBeEqualTo 0L
        }
    }

    /**
     * ### SELECT on Nullable Reference Column
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select on nullable reference column`(testDB: TestDB) {
        val firstTable = object: IntIdTable("firstTable") {}
        val secondTable = object: IntIdTable("secondTable") {
            val firstOpt = optReference("firstOpt", firstTable)
        }

        withTables(testDB, firstTable, secondTable) {
            val firstId = firstTable.insertAndGetId { }     // id = 1
            secondTable.insert {
                it[firstOpt] = firstId
            }
            secondTable.insert { }      // firstOpt = null

            secondTable.selectAll().count() shouldBeEqualTo 2L

            /**
             * ```sql
             * SELECT COUNT(*) FROM secondtable WHERE secondtable."firstOpt" = 1
             * ```
             */
            secondTable.selectAll()
                .where { secondTable.firstOpt eq firstId.value }
                .count() shouldBeEqualTo 1L

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM secondtable
             *  WHERE secondtable."firstOpt" <> 1  -- 1 != null
             *  ```
             */
            secondTable.selectAll()
                .where { secondTable.firstOpt neq firstId.value }
                .count() shouldBeEqualTo 0L

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM secondtable
             *  WHERE secondtable."firstOpt" IS NULL
             * ```
             */
            secondTable
                .selectAll()
                .where { secondTable.firstOpt eq null }   // secondTable.firstOpt.isNull()
                .count() shouldBeEqualTo 1L

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM secondtable
             *  WHERE secondtable."firstOpt" IS NOT NULL
             * ```
             */
            secondTable
                .selectAll()
                .where { secondTable.firstOpt neq null }   // secondTable.firstOpt.isNotNull() 
                .count() shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `SELECT 쿼리에서는 컬럼 길이를 검사하지 않습니다`(testDB: TestDB) {

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS stringtable (
         *      id SERIAL PRIMARY KEY,
         *      "name" VARCHAR(10) NOT NULL
         * )
         * ```
         */
        val stringTable = object: IntIdTable("StringTable") {
            val name = varchar("name", 10)
        }
        withTables(testDB, stringTable) {
            stringTable.insert {
                it[name] = "TestName"
            }
            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM stringtable
             *  WHERE stringtable."name" = 'TestName'
             * ```
             */
            stringTable.selectAll()
                .where { stringTable.name eq "TestName" }
                .count() shouldBeEqualTo 1L

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM stringtable
             *  WHERE stringtable."name" = 'YeM6yYtAorWNE2aWkchi'  -- 검색할 TEXT 길이가 컬럼 길이보다 길어도 실행은 된다.
             * ```
             */
            val moreLength = (stringTable.name.columnType as VarCharColumnType).colLength * 2
            val nameToSearch = Base58.randomString(moreLength)
            stringTable.selectAll()
                .where { stringTable.name eq nameToSearch }
                .count() shouldBeEqualTo 0L
        }
    }

    /**
     * ### SELECT with Comment
     *
     * Prefix Comment
     * ```sql
     * SELECT COUNT(*)
     *   FROM (/*additional_info*/ SELECT cities.city_id Cities_city_id,
     *                                    cities."name" Cities_name
     *                               FROM cities
     *                              WHERE cities."name" = 'Munich'
     *                              GROUP BY cities.city_id, cities."name"
     *                              LIMIT 1
     *        ) subquery
     * ```
     *
     * Suffix Comment
     * ```sql
     * SELECT COUNT(*)
     *   FROM (/*additional_info*/ SELECT cities.city_id Cities_city_id,
     *                                    cities."name" Cities_name
     *                               FROM cities
     *                              WHERE cities."name" = 'Munich'
     *                              GROUP BY cities.city_id, cities."name"
     *                              LIMIT 1 /*additional_info*/
     *       ) subquery
     * ```
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select with comment`(testDB: TestDB) {
        val text = "additional_info"
        val updatedText = "${text}_updated"

        withCitiesAndUsers(testDB) { cities, _, _ ->
            val query = cities.selectAll()
                .where { cities.name eq "Munich" }
                .limit(1)
                .groupBy(cities.id, cities.name)
            val originalQuery = query.copy()
            val originalSql = query.prepareSQL(this, false)

            // query 선두에 comment 추가
            val commentedFrontSql = query.comment(text).prepareSQL(this, false)
            commentedFrontSql shouldBeEqualTo "/*$text*/ $originalSql"

            // query에는 comment가 선두에 추가되었고, 후미에 추가한다.
            val commentedTwiceSql = query.comment(text, Query.CommentPosition.BACK).prepareSQL(this, false)
            commentedTwiceSql shouldBeEqualTo "/*$text*/ $originalSql /*$text*/"

            // 이미 query에는 comment가 존재하므로 IllegalStateException 발생
            expectException<IllegalStateException> {
                query.comment("Testing").toList()
            }

            val commentedBackSql = query
                .adjustComments(Query.CommentPosition.FRONT) // 새로운 주석이 지정되지 않았으므로, 기존 주석이 삭제된다.
                .adjustComments(Query.CommentPosition.BACK, updatedText)  // 기존 주석이 삭제되고, 새로운 주석이 추가된다.
                .prepareSQL(this, false)

            commentedBackSql shouldBeEqualTo "$originalSql /*$updatedText*/"

            originalQuery.comment(text).count() shouldBeEqualTo originalQuery.count()
            originalQuery.comment(text, Query.CommentPosition.BACK).count() shouldBeEqualTo originalQuery.count()
        }
    }

    /**
     * LIMIT and OFFSET
     *
     * ```sql
     * SELECT alphabet.letter FROM alphabet LIMIT 10
     * ```
     *
     * ```sql
     * SELECT alphabet.letter FROM alphabet LIMIT 10 OFFSET 8
     * ```
     *
     * ```sql
     * SELECT alphabet.letter FROM alphabet OFFSET 8
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select with limit and offset`(testDB: TestDB) {
        val alphabet = object: Table("alphabet") {
            val letter = char("letter")
        }

        withTables(testDB, alphabet) {
            val allLetters = ('A'..'Z').toList()
            val amount = 10
            val start = 8L

            alphabet.batchInsert(allLetters) { letter ->
                this[alphabet.letter] = letter
            }

            val limitResult = alphabet
                .selectAll()
                .limit(amount)
                .map { it[alphabet.letter] }
            limitResult shouldBeEqualTo allLetters.take(amount)

            val limitOffsetResult = alphabet.selectAll()
                .limit(amount)
                .offset(start)
                .map { it[alphabet.letter] }
            limitOffsetResult shouldBeEqualTo allLetters.drop(start.toInt()).take(amount)

            if (testDB !in TestDB.ALL_MYSQL_LIKE) {
                val offsetResult = alphabet
                    .selectAll()
                    .offset(start)
                    .map { it[alphabet.letter] }

                offsetResult shouldBeEqualTo allLetters.drop(start.toInt())
            }
        }
    }
}
