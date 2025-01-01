package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.Table
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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class SelectTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * simple selectAll
     *
     * ```sql
     * SELECT users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags
     *   FROM users
     *  WHERE users.id = 'andrey'
     * ```
     */
    @Test
    fun `select all with where clause`() {
        withCitiesAndUsers { _, users, _ ->
            users.selectAll()
                .where { users.id eq "andrey" }
                .forEach {
                    val userId = it[users.id]
                    val userName = it[users.name]
                    log.debug { "userId: $userId, userName: $userName" }

                    when (userId) {
                        "andrey" -> userName shouldBeEqualTo "Andrey"
                        else     -> error("Unexpected user id: $userId")
                    }
                }
        }
    }

    /**
     * WHERE clause - multiple conditions
     *
     * ```sql
     * SELECT users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags
     *   FROM users
     *  WHERE (users.id = 'andrey') AND (users."name" IS NOT NULL)
     *  ```
     */
    @Test
    fun `select all with where clause - multiple conditions - and`() {
        withCitiesAndUsers { _, users, _ ->
            users.selectAll()
                .where { users.id.eq("andrey") and users.name.isNotNull() }
                .forEach {
                    val userId = it[users.id]
                    val userName = it[users.name]
                    log.debug { "userId: $userId, userName: $userName" }

                    when (userId) {
                        "andrey" -> userName shouldBeEqualTo "Andrey"
                        else     -> error("Unexpected user id: $userId")
                    }
                }
        }
    }

    /**
     *
     * ```sql
     * SELECT users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags
     *   FROM users
     *  WHERE (users.id = 'andrey') OR (users."name" = 'Andrey')
     * ```
     */
    @Test
    fun `select all with where clause - multiple conditions - or`() {
        withCitiesAndUsers { _, users, _ ->
            users.selectAll()
                .where { users.id.eq("andrey") or users.name.eq("Andrey") }
                .forEach {
                    val userId = it[users.id]
                    val userName = it[users.name]
                    log.debug { "userId: $userId, userName: $userName" }

                    when (userId) {
                        "andrey" -> userName shouldBeEqualTo "Andrey"
                        else     -> error("Unexpected user id: $userId")
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
    @Test
    fun `select not`() {
        withCitiesAndUsers { _, users, _ ->
            users.selectAll()
                .where { users.id.neq("andrey") }
                .forEach {
                    val userId = it[users.id]
                    userId shouldNotBeEqualTo "andrey"
                }
        }
    }

    @Test
    fun `select sized iterable`() {
        withCitiesAndUsers { cities, users, _ ->
            cities.selectAll().shouldNotBeEmpty()
            cities.selectAll().where { cities.name eq "Qwertt" }.shouldBeEmpty()
            cities.selectAll().where { cities.name eq "Qwertt" }.count() shouldBeEqualTo 0L
            cities.selectAll().count() shouldBeEqualTo 3L

            val cityId: Int? = null
            // SELECT COUNT(*) FROM users WHERE users.city_id IS NULL
            users.selectAll().where { users.cityId eq cityId }.count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `inList with single expression 01`() {
        withCitiesAndUsers { _, users, _ ->
            val r1 = users.selectAll()
                .where { users.id inList listOf("andrey", "alex") }
                .orderBy(users.name)
                .toList()

            r1.size shouldBeEqualTo 2
            r1[0][users.name] shouldBeEqualTo "Alex"
            r1[1][users.name] shouldBeEqualTo "Andrey"

            val r2 = users.selectAll()
                .where { users.id notInList listOf("ABC", "DEF") }
                .toList()

            users.selectAll().count().toInt() shouldBeEqualTo r2.size
        }
    }

    @Test
    fun `inList with single expression 02`() {
        withCitiesAndUsers { cities, _, _ ->
            val cityIds = cities.selectAll().map { it[cities.id] }.take(2)
            val r = cities.selectAll()
                .where { cities.id inList cityIds }
            // .where { cities.id inSubQuery cities.select(cities.id) }

            r.count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `inList with pair expression 01`() {
        withCitiesAndUsers { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id to users.name inList listOf("andrey" to "Andrey", "alex" to "Alex")
                }
                .orderBy(users.name)
                .toList()

            rows shouldHaveSize 2
            rows[0][users.name] shouldBeEqualTo "Alex"
            rows[1][users.name] shouldBeEqualTo "Andrey"
        }
    }

    @Test
    fun `inList with pair expression 02`() {
        withCitiesAndUsers { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id to users.name inList listOf("andrey" to "Andrey")
                }
                .toList()

            rows shouldHaveSize 1
            rows[0][users.name] shouldBeEqualTo "Andrey"
        }
    }


    @Test
    fun `inList with pair expression and emptyList`() {
        withCitiesAndUsers { _, users, _ ->
            val rows = users.selectAll()
                .where {
                    users.id to users.name inList emptyList()
                }
                .toList()

            rows.shouldBeEmpty()
        }
    }


    @Test
    fun `notInList with pair expression and emptyList`() {
        withCitiesAndUsers { _, users, _ ->
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
     * ```sql
     * SELECT USERS.ID,
     *        USERS."name",
     *        USERS.CITY_ID,
     *        USERS.FLAGS
     *   FROM USERS
     *  WHERE (USERS.ID, USERS."name", USERS.CITY_ID) NOT IN (('andrey', 'Andrey', 1), ('sergey', 'Sergey', 2))
     * ```
     *
     * ### inList with triple expressions
     * ```sql
    SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS FROM USERS WHERE (USERS.ID, USERS."name", USERS.CITY_ID) IN (('alex', 'Alex', NULL), ('andrey', 'Andrey', 1))
     * ```
     */
    @Disabled("제대로 작동하지 않는다. notInList 시에는 3개가 조회되어야 하는데, eugene 만 조회된다.")
    @Test
    fun `inList with triple expressions`() {
        withCitiesAndUsers { _, users, _ ->
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
                log.debug { "row: $it" }
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

            rows2 shouldHaveSize 2
        }
    }

    @Test
    fun `inList with Multiple columns`() {
        val tester = object: Table("tester") {
            val num1 = integer("num_1")
            val num2 = double("num_2")
            val num3 = varchar("num_3", 8)
            val num4 = long("num_4")
        }

        fun Int.toColumnValue(index: Int) = when (index) {
            1    -> toDouble()
            2    -> toString()
            3    -> toLong()
            else -> this
        }

        withTables(tester) {
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
            val allSameNumbers = List(3) { n -> List(4) { n.toColumnValue(it) } }

            // SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
            //   FROM tester
            //  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4) IN ((0, 0.0, '0', 0), (1, 1.0, '1', 1), (2, 2.0, '2', 2))
            val result1 = tester.selectAll().where { tester.columns inList allSameNumbers }.toList()
            result1.size shouldBeEqualTo expected

            // SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
            //   FROM tester
            //  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4) = (0, 0.0, '0', 0)
            val result2 = tester.selectAll().where { tester.columns inList listOf(allSameNumbers.first()) }.toList()
            result2.size shouldBeEqualTo 1


            // (0, 1.0, '2', 3), (1, 2.0, '3', 4), (2, 3.0, '4', 5)
            val allDifferentNumbers = List(3) { n -> List(4) { (n + it).toColumnValue(it) } }

            // SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
            //   FROM tester
            //  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4) NOT IN ((0, 1.0, '2', 3), (1, 2.0, '3', 4), (2, 3.0, '4', 5))
            val result3 = tester.selectAll().where { tester.columns notInList allDifferentNumbers }.toList()
            result3.size shouldBeEqualTo expected

            // SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
            //   FROM tester
            //  WHERE TRUE
            val result4 = tester.selectAll().where { tester.columns notInList emptyList() }.toList()
            result4.size shouldBeEqualTo expected

            // SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
            //   FROM tester
            //  WHERE (tester.num_1, tester.num_2, tester.num_3, tester.num_4) NOT IN ((0, 0.0, '0', 0), (1, 1.0, '1', 1), (2, 2.0, '2', 2))
            val result5 = tester.selectAll().where { tester.columns notInList allSameNumbers }.toList()
            result5.shouldBeEmpty()

            // SELECT tester.num_1, tester.num_2, tester.num_3, tester.num_4
            //   FROM tester
            //  WHERE FALSE
            val result6 = tester.selectAll().where { tester.columns inList emptyList() }.toList()
            result6.shouldBeEmpty()
        }
    }

    @Test
    fun `inList with entityID columns`() {
        withTables(EntityTest.Posts, EntityTest.Boards, EntityTest.Categories) {
            val board1 = EntityTest.Board.new {
                name = "board1"
            }
            val post1 = EntityTest.Post.new {
                board = board1
            }
            EntityTest.Post.new {
                category = EntityTest.Category.new { title = "category1" }
            }

            // SELECT posts.id, posts.board, posts.parent, posts.category, posts."optCategory" FROM posts WHERE posts.board = 1
            val result1 = EntityTest.Posts.selectAll()
                .where {
                    EntityTest.Posts.board inList listOf(board1.id)
                }
                .singleOrNull()?.get(EntityTest.Posts.id)
            result1 shouldBeEqualTo post1.id

            // SELECT board.id, board."name" FROM board WHERE board.id IN (1, 2, 3, 4, 5)
            val result2 = EntityTest.Board.find {
                EntityTest.Boards.id inList listOf(1, 2, 3, 4, 5)
            }.singleOrNull()
            result2 shouldBeEqualTo board1

            // SELECT board.id, board."name" FROM board WHERE board.id NOT IN (1, 2, 3, 4, 5)
            val result3 = EntityTest.Board.find {
                EntityTest.Boards.id notInList listOf(1, 2, 3, 4, 5)
            }.singleOrNull()
            result3.shouldBeNull()
        }
    }

    /**
     * ### In with SubQuery
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM cities
     *  WHERE cities.city_id IN (SELECT cities.city_id
     *                             FROM cities
     *                            WHERE cities.city_id = 2)
     * ```
     */
    @Test
    fun `inSubQuery 01`() {
        withCitiesAndUsers { cities, _, _ ->
            val r = cities.selectAll()
                .where { cities.id inSubQuery cities.select(cities.id).where { cities.id eq 2 } }

            r.count() shouldBeEqualTo 1L
        }
    }

    /**
     * ### NOT IN with SubQuery
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM CITIES
     *  WHERE CITIES.CITY_ID NOT IN (SELECT CITIES.CITY_ID FROM CITIES)
     * ```
     */
    @Test
    fun `notInSubQuery with NoData`() {
        withCitiesAndUsers { cities, _, _ ->
            val r = cities.selectAll()
                .where { cities.id notInSubQuery cities.select(cities.id) }

            r.count() shouldBeEqualTo 0L
        }
    }

    private val testDBsSupportingInAnyAllFromTables = TestDB.ALL_POSTGRES + TestDB.ALL_H2 + TestDB.MYSQL_V8

    @Test
    fun `inTable example`() {
        withDb(testDBsSupportingInAnyAllFromTables) {
            withSalesAndSomeAmounts { _, sales, someAmounts ->
                val rows = sales.selectAll()
                    .where { sales.amount inTable someAmounts }
                rows.count() shouldBeEqualTo 2L
            }
        }
    }

    @Test
    fun `notInTable example`() {
        withDb(testDBsSupportingInAnyAllFromTables) {
            withSalesAndSomeAmounts { _, sales, someAmounts ->
                val rows = sales.selectAll()
                    .where { sales.amount notInTable someAmounts }
                rows.count() shouldBeEqualTo 5L
            }
        }
    }

    private val testDBsSupportingAnyAndAllFromSubQueries = TestDB.ALL
    private val testDBsSupportingAnyAndAllFromArrays = TestDB.ALL_POSTGRES // + TestDB.ALL_H2

    /**
     * ### Eq AnyFrom SubQuery
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM CITIES
     *  WHERE CITIES.CITY_ID = ANY (SELECT CITIES.CITY_ID FROM CITIES WHERE CITIES.CITY_ID = 2)
     * ```
     */
    @Test
    fun `eq AnyFrom SubQuery`() {
        withDb(testDBsSupportingAnyAndAllFromSubQueries) {
            withCitiesAndUsers { cities, _, _ ->
                val rows = cities.selectAll()
                    .where {
                        cities.id eq anyFrom(cities.select(cities.id).where { cities.id eq 2 })
                    }

                rows.count() shouldBeEqualTo 1L
            }
        }
    }

    /**
     * ### Neq AnyFrom SubQuery
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM CITIES
     *  WHERE CITIES.CITY_ID <> ANY (SELECT CITIES.CITY_ID FROM CITIES WHERE CITIES.CITY_ID = 2)
     * ```
     */
    @Test
    fun `neq AnyFrom SubQuery`() {
        withDb(testDBsSupportingAnyAndAllFromSubQueries) {
            withCitiesAndUsers { cities, _, _ ->
                val rows = cities.selectAll()
                    .where {
                        cities.id neq anyFrom(cities.select(cities.id).where { cities.id eq 2 })
                    }

                rows.count() shouldBeEqualTo 2L
            }
        }
    }

    /**
     * ### Eq AnyFrom Array
     *
     * ```sql
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS
     *   FROM USERS
     *  WHERE USERS.ID = ANY (ARRAY ['andrey','alex'])
     *  ORDER BY USERS."name" ASC
     * ```
     */
    @Test
    fun `eq AnyFrom Array`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withCitiesAndUsers { cities, users, _ ->
                val rows = users.selectAll()
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
    }

    /**
     * ### Eq AnyFrom List
     *
     * ```sql
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS
     *   FROM USERS
     *  WHERE USERS.ID = ANY (ARRAY ['andrey','alex'])
     *  ORDER BY USERS."name" ASC
     * ```
     */
    @Test
    fun `eq AnyFrom List`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withCitiesAndUsers { cities, users, _ ->
                val rows = users.selectAll()
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
    }

    /**
     * ### Neq AnyFrom Array
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM USERS
     *  WHERE USERS.ID <> ANY (ARRAY ['andrey'])
     * ```
     */
    @Test
    fun `neq AnyFrom Array`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withCitiesAndUsers { _, users, _ ->
                val rows = users.selectAll()
                    .where {
                        users.id neq anyFrom(arrayOf("andrey"))
                    }
                    .orderBy(users.name)

                rows.count() shouldBeEqualTo 4L
            }
        }
    }

    /**
     * ### Neq AnyFrom List
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM USERS
     *  WHERE USERS.ID <> ANY (ARRAY ['andrey'])
     * ```
     */
    @Test
    fun `neq AnyFrom List`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withCitiesAndUsers { _, users, _ ->
                val rows = users.selectAll()
                    .where {
                        users.id neq anyFrom(listOf("andrey"))
                    }
                    .orderBy(users.name)

                rows.count() shouldBeEqualTo 4L
            }
        }
    }

    /**
     * ### Neq AnyFrom Empty Array
     *
     * ```sql
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS
     *   FROM USERS
     *  WHERE USERS.ID = ANY (ARRAY [])
     *  ORDER BY USERS."name" ASC
     * ```
     */
    @Test
    fun `neq AnyFrom empty array`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withCitiesAndUsers { _, users, _ ->
                val rows = users.selectAll()
                    .where {
                        users.id eq anyFrom(emptyArray())
                    }
                    .orderBy(users.name)

                rows.shouldBeEmpty()
            }
        }
    }

    /**
     * ### Neq AnyFrom Empty List
     *
     * ```sql
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS
     *   FROM USERS
     *  WHERE USERS.ID = ANY (ARRAY [])
     *  ORDER BY USERS."name" ASC
     * ```
     */
    @Test
    fun `neq AnyFrom empty list`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withCitiesAndUsers { _, users, _ ->
                val rows = users.selectAll()
                    .where {
                        users.id eq anyFrom(emptyList())
                    }
                    .orderBy(users.name)

                rows.shouldBeEmpty()
            }
        }
    }

    /**
     * ### Greater Eq AnyFrom Array
     *
     * ```sql
     * SELECT SALES."year", SALES."month", SALES.PRODUCT, SALES.AMOUNT
     *   FROM SALES
     *  WHERE SALES.AMOUNT >= ANY (ARRAY [100,1000])
     *  ORDER BY SALES.AMOUNT ASC
     * ```
     */
    @Test
    fun `greater eq AnyFrom Array`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withSales { _, sales ->
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
    }


    /**
     * ### Greater Eq AnyFrom List
     *
     * ```sql
     * SELECT SALES."year", SALES."month", SALES.PRODUCT, SALES.AMOUNT
     *   FROM SALES
     *  WHERE SALES.AMOUNT >= ANY (ARRAY [100,1000])
     *  ORDER BY SALES.AMOUNT ASC
     * ```
     */
    @Test
    fun `greater eq AnyFrom List`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withSales { _, sales ->
                val amounts = listOf(100, 1000).map { it.toBigDecimal() }

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
    }

    @Test
    fun `Eq AnyFrom Table`() {
        withDb(testDBsSupportingInAnyAllFromTables) {
            withSalesAndSomeAmounts { _, sales, someAmounts ->
                val rows = sales.selectAll()
                    .where { sales.amount eq anyFrom(someAmounts.select(someAmounts.amount)) }
                rows.count() shouldBeEqualTo 2L
            }
        }
    }

    @Test
    fun `Neq AnyFrom Table`() {
        withDb(testDBsSupportingInAnyAllFromTables) {
            withSalesAndSomeAmounts { _, sales, someAmounts ->
                val rows = sales.selectAll()
                    .where { sales.amount neq anyFrom(someAmounts.select(someAmounts.amount)) }
                rows.count() shouldBeEqualTo 5L
            }
        }
    }

    @Test
    fun `Greater Eq AllFrom SubQuery`() {
        withDb(testDBsSupportingAnyAndAllFromSubQueries) {
            withSales { _, sales ->
                val rows = sales.selectAll()
                    .where {
                        sales.amount greaterEq allFrom(sales.select(sales.amount).where { sales.product eq "tea" })
                    }
                    .orderBy(sales.amount)
                    .map { it[sales.product] }

                rows shouldHaveSize 4
                rows.first() shouldBeEqualTo "tea"
                rows.drop(1).forEach { it shouldBeEqualTo "coffee" }
            }
        }
    }

    /**
     * ### Greater Eq AllFrom Array
     *
     * ```sql
     * SELECT SALES."year", SALES."month", SALES.PRODUCT, SALES.AMOUNT
     *   FROM SALES
     *  WHERE SALES.AMOUNT >= ALL (ARRAY [100,1000])
     * ```
     */
    @Test
    fun `Greater Eq AllFrom Array`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withSales { _, sales ->
                val amounts = arrayOf(100, 1000).map { it.toBigDecimal() }.toTypedArray()

                val rows = sales.selectAll()
                    .where {
                        sales.amount greaterEq allFrom(amounts)
                    }
                    .toList()

                rows shouldHaveSize 3
                rows.forEach { it[sales.product] shouldBeEqualTo "coffee" }
            }
        }
    }


    /**
     * ### Greater Eq AllFrom Array
     *
     * ```sql
     * SELECT SALES."year", SALES."month", SALES.PRODUCT, SALES.AMOUNT
     *   FROM SALES
     *  WHERE SALES.AMOUNT >= ALL (ARRAY [100,1000])
     * ```
     */
    @Test
    fun `Greater Eq AllFrom List`() {
        withDb(testDBsSupportingAnyAndAllFromArrays) {
            withSales { _, sales ->
                val amounts = arrayOf(100, 1000).map { it.toBigDecimal() }

                val rows = sales.selectAll()
                    .where {
                        sales.amount greaterEq allFrom(amounts)
                    }
                    .toList()

                rows shouldHaveSize 3
                rows.forEach { it[sales.product] shouldBeEqualTo "coffee" }
            }
        }
    }

    @Test
    fun `Greater Eq AllFrom Table`() {
        withDb(testDBsSupportingInAnyAllFromTables) {
            withSalesAndSomeAmounts { _, sales, someAmounts ->
                val rows = sales.selectAll()
                    .where { sales.amount greaterEq allFrom(someAmounts) }
                    .toList()

                rows shouldHaveSize 3
                rows.forEach { it[sales.product] shouldBeEqualTo "coffee" }
            }
        }
    }

    @Test
    fun `select distinct`() {
        val tbl = DMLTestData.Cities
        withTables(tbl) {
            tbl.insert { it[tbl.name] = "test" }
            tbl.insert { it[tbl.name] = "test" }

            tbl.selectAll().count() shouldBeEqualTo 2L
            tbl.selectAll().withDistinct().count() shouldBeEqualTo 2L

            tbl.select(tbl.name).withDistinct().count() shouldBeEqualTo 1L
            tbl.selectAll().withDistinctOn(tbl.name).count() shouldBeEqualTo 1L
        }
    }

    /**
     * ### Compound Operations
     *
     * Compound OR
     * ```sql
     * SELECT USERS.ID, USERS."name", USERS.CITY_ID, USERS.FLAGS
     *   FROM USERS
     *  WHERE (USERS."name" = 'Andrey')
     *     OR (USERS."name" = 'Sergey')
     *     OR (USERS."name" = 'Eugene')
     *     OR (USERS."name" = 'Alex')
     *     OR (USERS."name" = 'Something')
     * ```
     *
     * Compound AND
     * ```sql
     * SELECT COUNT(*)
     *   FROM USERS
     *  WHERE (USERS."name" = 'Andrey')
     *    AND (USERS."name" = 'Sergey')
     *    AND (USERS."name" = 'Eugene')
     *    AND (USERS."name" = 'Alex')
     *    AND (USERS."name" = 'Something')
     * ```
     *
     */
    @Test
    fun `compound operations`() {
        withCitiesAndUsers { _, users, _ ->
            val allUsers = setOf(
                "Andrey",
                "Sergey",
                "Eugene",
                "Alex",
                "Something"
            )
            val orOp = allUsers.map { Op.build { users.name eq it } }.compoundOr()
            val userNameOr = users.selectAll().where(orOp).map { it[users.name] }.toSet()
            userNameOr shouldBeEqualTo allUsers

            val andOp = allUsers.map { Op.build { users.name eq it } }.compoundAnd()
            users.selectAll().where(andOp).count() shouldBeEqualTo 0
        }
    }

    @Test
    fun `select on nullable reference column`() {
        val firstTable = object: IntIdTable("firstTable") {}
        val secondTable = object: IntIdTable("secondTable") {
            val firstOpt = optReference("firstOpt", firstTable)
        }

        withTables(firstTable, secondTable) {
            val firstId = firstTable.insertAndGetId { }
            secondTable.insert {
                it[firstOpt] = firstId
            }
            secondTable.insert { }

            secondTable.selectAll().count() shouldBeEqualTo 2L
            // SELECT COUNT(*) FROM SECONDTABLE WHERE SECONDTABLE."firstOpt" = 1
            secondTable.selectAll().where { secondTable.firstOpt eq firstId.value }.count() shouldBeEqualTo 1L

            // SELECT COUNT(*) FROM SECONDTABLE WHERE SECONDTABLE."firstOpt" <> 1  // 1 != null
            secondTable.selectAll().where { secondTable.firstOpt neq firstId.value }.count() shouldBeEqualTo 0L

            // SELECT COUNT(*) FROM SECONDTABLE WHERE SECONDTABLE."firstOpt" IS NULL
            secondTable.selectAll().where { secondTable.firstOpt eq null }.count() shouldBeEqualTo 1L

            // SELECT COUNT(*) FROM SECONDTABLE WHERE SECONDTABLE."firstOpt" IS NOT NULL
            secondTable.selectAll().where { secondTable.firstOpt neq null }.count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `SELECT 쿼리에서는 컬럼 길이를 검사하지 않습니다`() {
        val stringTable = object: IntIdTable("StringTable") {
            val name = varchar("name", 10)
        }
        withTables(stringTable) {
            stringTable.insert {
                it[name] = "TestName"
            }

            stringTable.selectAll()
                .where { stringTable.name eq "TestName" }
                .count() shouldBeEqualTo 1L

            val veryLongString = "1".repeat(255)
            stringTable.selectAll()
                .where { stringTable.name eq veryLongString }
                .count() shouldBeEqualTo 0L
        }
    }

    /**
     *
     * ### SELECT with Comment
     *
     * Prefix Comment
     * ```sql
     * SELECT COUNT(*)
     *   FROM (/*additional_info*/ SELECT CITIES.CITY_ID Cities_city_id, CITIES."name" Cities_name
     *                               FROM CITIES
     *                              WHERE CITIES."name" = 'Munich'
     *                              GROUP BY CITIES.CITY_ID, CITIES."name" LIMIT 1
     *        ) subquery
     * ```
     *
     * Suffix Comment
     * ```sql
     * SELECT COUNT(*)
     *   FROM (/*additional_info*/ SELECT CITIES.CITY_ID Cities_city_id, CITIES."name" Cities_name
     *                               FROM CITIES
     *                              WHERE CITIES."name" = 'Munich'
     *                              GROUP BY CITIES.CITY_ID, CITIES."name" LIMIT 1
     *    *         /*additional_info*/
     *         ) subquery
     * ```
     *
     */
    @Test
    fun `select with comment`() {
        val text = "additional_info"
        val updatedText = "${text}_updated"

        withCitiesAndUsers { cities, _, _ ->
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
     * SELECT ALPHABET.LETTER FROM ALPHABET LIMIT 10
     * ```
     *
     * ```sql
     * SELECT ALPHABET.LETTER FROM ALPHABET LIMIT 10 OFFSET 8
     * ```
     *
     * ```sql
     * SELECT ALPHABET.LETTER FROM ALPHABET OFFSET 8
     * ```
     */
    @Test
    fun `select with limit and offset`() {
        val alphabet = object: Table("alphabet") {
            val letter = char("letter")
        }

        withTables(alphabet) { testDb ->
            val allLetters = ('A'..'Z').toList()
            val amount = 10
            val start = 8L

            alphabet.batchInsert(allLetters) { letter ->
                this[alphabet.letter] = letter
            }

            val limitResult = alphabet.selectAll().limit(amount).map { it[alphabet.letter] }
            limitResult shouldBeEqualTo allLetters.take(amount)

            val limitOffsetResult = alphabet.selectAll().limit(amount).offset(start).map { it[alphabet.letter] }
            limitOffsetResult shouldBeEqualTo allLetters.drop(start.toInt()).take(amount)

            if (testDb !in TestDB.ALL_MYSQL) {
                val offsetResult = alphabet.selectAll().offset(start).map { it[alphabet.letter] }
                offsetResult shouldBeEqualTo allLetters.drop(start.toInt())
            }
        }
    }
}
