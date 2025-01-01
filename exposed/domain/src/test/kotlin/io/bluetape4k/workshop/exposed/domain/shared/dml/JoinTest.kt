package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.withTables
import nl.altindag.log.LogCaptor
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.junit.jupiter.api.Test

class JoinTest: AbstractExposedTest() {

    /**
     * inner join 이 자동으로 지정되지만, 수동으로 where 절에 추가할 수도 있다
     *
     * ```sql
     * SELECT users."name", cities."name"
     * FROM users INNER JOIN cities ON cities.city_id = users.city_id
     * WHERE ((users.id = 'andrey') OR (users."name" = 'Sergey')) AND (users.city_id = cities.city_id)
     * ```
     */
    @Test
    fun `manual join`() {
        withCitiesAndUsers { cities, users, _ ->
            (users innerJoin cities).select(users.name, cities.name)
                .where { (users.id.eq("andrey") or users.name.eq("Sergey")) and users.cityId.eq(cities.id) }
                .forEach {
                    val userName = it[users.name]
                    val cityName = it[cities.name]
                    when (userName) {
                        "Andrey" -> cityName shouldBeEqualTo "St. Petersburg"
                        "Sergey" -> cityName shouldBeEqualTo "Munich"
                        else     -> error("Unexpected user $userName")
                    }
                }

        }
    }

    /**
     * ```sql
     * SELECT users."name", users.city_id, cities."name"
     * FROM users INNER JOIN cities ON cities.city_id = users.city_id
     * WHERE (cities."name" = 'St. Petersburg')
     *    OR (users.city_id IS NULL)
     * ```
     */
    @Test
    fun `join with foreign key`() {
        withCitiesAndUsers { cities, users, _ ->
            val stPetersburgUser = (users innerJoin cities).select(users.name, users.cityId, cities.name)
                .where { cities.name.eq("St. Petersburg") or users.cityId.isNull() }.single()

            stPetersburgUser[users.name] shouldBeEqualTo "Andrey"
            stPetersburgUser[cities.name] shouldBeEqualTo "St. Petersburg"
        }
    }

    /**
     * Triple join
     *
     * ```sql
     * SELECT cities.city_id,
     *        cities."name",
     *        users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags,
     *        userdata.user_id,
     *        userdata.comment,
     *        userdata."value"
     * FROM cities
     *      INNER JOIN users ON cities.city_id = users.city_id
     *      INNER JOIN userdata ON users.id = userdata.user_id
     * ORDER BY users.id ASC
     * ```
     */
    @Test
    fun `triple join`() {
        withCitiesAndUsers { cities, users, userData ->
            val rows = (cities innerJoin users innerJoin userData)
                .selectAll()
                .orderBy(users.id)
                .toList()

            rows shouldHaveSize 2

            rows[0][users.name] shouldBeEqualTo "Eugene"
            rows[0][userData.comment] shouldBeEqualTo "Comment for Eugene"
            rows[0][cities.name] shouldBeEqualTo "Munich"

            rows[1][users.name] shouldBeEqualTo "Sergey"
            rows[1][userData.comment] shouldBeEqualTo "Comment for Sergey"
            rows[1][cities.name] shouldBeEqualTo "Munich"
        }
    }

    /**
     * Many-to-many Join
     *
     * ```sql
     * SELECT numbers.id,
     *        "map".id_ref,
     *        "map".name_ref,
     *        "names"."name"
     * FROM numbers
     *      INNER JOIN "map" ON numbers.id = "map".id_ref
     *      INNER JOIN "names" ON "names"."name" = "map".name_ref
     * ```
     */
    @Test
    fun `many-to-many join`() {
        val numbers = object: Table("numbers") {
            val id = integer("id")
            override val primaryKey = PrimaryKey(id)
        }
        val names = object: Table("names") {
            val name = varchar("name", 10)
            override val primaryKey = PrimaryKey(name)
        }
        val map = object: Table("map") {
            val idRef = integer("id_ref") references numbers.id
            val nameRef = varchar("name_ref", 10) references names.name
        }

        withTables(numbers, names, map) {
            numbers.insert { it[id] = 1 }
            numbers.insert { it[id] = 2 }
            names.insert { it[name] = "Foo" }
            names.insert { it[name] = "Bar" }
            map.insert {
                it[idRef] = 2
                it[nameRef] = "Foo"
            }

            val r = (numbers innerJoin map innerJoin names).selectAll().toList()
            r shouldHaveSize 1
            r[0][numbers.id] shouldBeEqualTo 2
            r[0][names.name] shouldBeEqualTo "Foo"
        }
    }

    /**
     * Cross Join
     *
     * ```sql
     * SELECT
     *      users."name",
     *      users.city_id,
     *      cities."name"
     * FROM cities CROSS JOIN users
     * WHERE cities."name" = 'St. Petersburg'
     * ```
     */
    @Test
    fun `cross join`() {
        withCitiesAndUsers { cities, users, _ ->
            val allUsersToStPetersburg = (cities crossJoin users)
                .select(users.name, users.cityId, cities.name)
                .where { cities.name.eq("St. Petersburg") }
                .map { it[users.name] to it[cities.name] }

            val allUsers = setOf(
                "Andrey",
                "Sergey",
                "Eugene",
                "Alex",
                "Something"
            )

            allUsersToStPetersburg.map { it.second }.distinct() shouldBeEqualTo listOf("St. Petersburg")
            allUsersToStPetersburg.map { it.first }.toSet() shouldBeEqualTo allUsers
        }
    }

    /**
     * Multiple reference join
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM foo INNER JOIN bar ON foo.id = bar.foo AND foo.baz = bar.baz
     * ```
     *
     */
    @Test
    fun `multiple reference join 01`() {
        val foo = object: IntIdTable("foo") {
            val baz = integer("baz").uniqueIndex()
        }
        val bar = object: IntIdTable("bar") {
            val foo = reference("foo", foo)
            val baz = integer("baz") references foo.baz
        }
        withTables(foo, bar) {
            val fooId = foo.insertAndGetId {
                it[baz] = 5
            }

            bar.insert {
                it[this.foo] = fooId
                it[baz] = 5
            }

            val result = foo.innerJoin(bar).selectAll()
            result.count() shouldBeEqualTo 1L
        }
    }

    /**
     * Multiple primary key <-> foreign key join 은 허용되지 않습니다.
     */
    @Test
    fun `multiple reference join 02`() {
        val foo = object: IntIdTable("foo") {
            val baz = integer("baz").uniqueIndex()
        }
        val bar = object: IntIdTable("bar") {
            val foo = reference("foo", foo)       // foreign key to foo primary key
            val foo2 = reference("foo2", foo)     // foreign key to foo primary key
            val baz = integer("baz") references foo.baz
        }
        withTables(foo, bar) {
            expectException<IllegalStateException> {
                val fooId = foo.insertAndGetId {
                    it[baz] = 5
                }

                bar.insert {
                    it[this.foo] = fooId
                    it[this.foo2] = fooId
                    it[baz] = 5
                }

                val result = foo.innerJoin(bar).selectAll()
                result.count() shouldBeEqualTo 1L
            }
        }
    }

    /**
     * 동일 테이블을 Alias 를 이용해 Join 하기
     *
     * ```sql
     * SELECT
     *      users.id,
     *      users."name",
     *      users.city_id,
     *      users.flags,
     *      u2.id,
     *      u2."name",
     *      u2.city_id,
     *      u2.flags
     * FROM users LEFT JOIN users u2 ON u2.id = 'smth'
     * WHERE users.id = 'alex'
     * ```
     */
    @Test
    fun `join with alias 01`() {
        withCitiesAndUsers { _, users, _ ->
            val usersAlias = users.alias("u2")
            val resultRow = Join(users)
                .join(usersAlias, JoinType.LEFT, usersAlias[users.id], stringLiteral("smth"))
                .selectAll()
                .where { users.id eq "alex" }
                .single()

            resultRow[users.name] shouldBeEqualTo "Alex"
            resultRow[usersAlias[users.name]] shouldBeEqualTo "Something"
        }
    }

    /**
     * Nested Join
     *
     * ```
     * SELECT COUNT(*)
     *   FROM cities
     *       INNER JOIN (users INNER JOIN userdata ON users.id = userdata.user_id)
     *               ON cities.city_id = users.city_id
     * ```
     */
    @Test
    fun `join with join`() {
        withCitiesAndUsers { cities, users, userData ->
            val rows = (cities innerJoin (users innerJoin userData)).selectAll()
            rows.count() shouldBeEqualTo 2L
        }
    }

    /**
     * Join with additional constraint
     *
     * ```sql
     * SELECT COUNT(*)
     *   FROM cities
     *        INNER JOIN users "name" ON cities.city_id = "name".city_id
     *        AND ((cities.city_id > 1) AND (cities."name" <> "name"."name"))
     * ```
     */
    @Test
    fun `join with additional constraint`() {
        withCitiesAndUsers { cities, users, _ ->
            val usersAlias = users.alias("name")
            val join = cities
                .join(usersAlias, JoinType.INNER, cities.id, usersAlias[users.cityId]) {
                    cities.id greater 1 and (cities.name.neq(usersAlias[users.name]))
                }

            val rows = join.selectAll()
            rows.count() shouldBeEqualTo 2L
        }
    }

    /**
     * Left join regression
     *
     * ```
     * SELECT jointable."dataCol"
     *   FROM maintable LEFT JOIN jointable ON jointable."idCol" = maintable."idCol"
     * ```
     */
    @Test
    fun `no warnings on left join regression`() {
        val logCaptor = LogCaptor.forName(exposedLogger.name)

        val mainTable = object: Table("maintable") {
            val id = integer("idCol")
        }
        val joinTable = object: Table("jointable") {
            val id = integer("idCol")
            val data = integer("dataCol").default(42)
        }

        withTables(mainTable, joinTable) {
            mainTable.insert { it[id] = 2 }

            val data = mainTable.join(joinTable, JoinType.LEFT, joinTable.id, mainTable.id)
                .select(joinTable.data)
                .single()
                .getOrNull(joinTable.data)

            data.shouldBeNull()

            // Assert no logging took place
            logCaptor.warnLogs.shouldBeEmpty()
            logCaptor.errorLogs.shouldBeEmpty()
        }
    }
}
