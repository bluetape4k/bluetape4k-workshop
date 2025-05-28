package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.dml.withCitiesAndUsers
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XTable
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YTable
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.core.decimalLiteral
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.joinQuery
import org.jetbrains.exposed.v1.core.lastQueryAlias
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.orWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class AliasesTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count alias ClassCastException`(testDB: TestDB) {

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS stables (
         *      id uuid PRIMARY KEY,
         *      "name" VARCHAR(256) NOT NULL
         * );
         *
         * ALTER TABLE stables
         *      ADD CONSTRAINT stables_name_unique UNIQUE ("name");
         * ```
         */
        val stables = object: UUIDTable("Stables") {
            val name = varchar("name", 256).uniqueIndex()
        }

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS facilities (
         *      id uuid PRIMARY KEY,
         *      stable_id uuid NOT NULL,
         *      "name" VARCHAR(256) NOT NULL,
         *
         *      CONSTRAINT fk_facilities_stable_id__id FOREIGN KEY (stable_id)
         *      REFERENCES stables(id) ON DELETE RESTRICT ON UPDATE RESTRICT
         * );
         * ```
         */
        val facilities = object: UUIDTable("Facilities") {
            val stableId = reference("stable_id", stables)
            val name = varchar("name", 256)
        }

        withTables(testDB, stables, facilities) {
            val stableId = stables.insertAndGetId {
                it[name] = "Stable1"
            }
            stables.insertAndGetId {
                it[name] = "Stable2"
            }

            facilities.insertAndGetId {
                it[facilities.stableId] = stableId
                it[facilities.name] = "Facility1"
            }

            val fcAlias = facilities.name.count().alias("fc")
            val fAlias = facilities
                .select(facilities.stableId, fcAlias)
                .groupBy(facilities.stableId)
                .alias("f")

            val sliceColumns = stables.columns + fAlias[fcAlias]

            /**
             * ```sql
             * SELECT stables.id, stables."name", f.fc
             *   FROM stables
             *      LEFT JOIN (SELECT facilities.stable_id,
             *                        COUNT(facilities."name") fc
             *                   FROM facilities
             *                  GROUP BY facilities.stable_id
             *                 ) f
             *           ON stables.id = f.stable_id
             *  GROUP BY stables.id, stables."name", f.fc
             * ```
             */
            val stats: Map<String, Long?> = stables.join(fAlias, JoinType.LEFT, stables.id, fAlias[facilities.stableId])
                .select(sliceColumns)
                .groupBy(*sliceColumns.toTypedArray())
                .associate {
                    it[stables.name] to it[fAlias[fcAlias]]
                }

            stats.forEach { (key, value) ->
                log.debug { "key: $key, value: $value" }
            }
            stats.size shouldBeEqualTo 2
            stats["Stable1"] shouldBeEqualTo 1
            stats["Stable2"].shouldBeNull()
        }
    }

    /**
     * Postgres:
     * ```sql
     * SELECT users.id, users."name", users.city_id, users.flags, u2.city_id, u2.m
     *   FROM users
     *      INNER JOIN (SELECT users.city_id,
     *                         MAX(users."name") m
     *                    FROM users
     *                   GROUP BY users.city_id
     *                 ) u2
     *            ON u2.m = users."name"
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `join subquery 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val expAlias = users.name.max().alias("m")
            val usersAlias = users.select(users.cityId, expAlias).groupBy(users.cityId).alias("u2")

            val resultRows = Join(users)
                .join(usersAlias, JoinType.INNER, usersAlias[expAlias], users.name)
                .selectAll()
                .toList()

            resultRows.forEach {
                log.debug { "row: ${it[users.id]}" }
            }
            resultRows shouldHaveSize 3
        }
    }

    /**
     * Postgres:
     * ```sql
     * -- count
     * SELECT COUNT(*)
     *   FROM users
     *      INNER JOIN (SELECT users.city_id,
     *                         MAX(users."name") m
     *                    FROM users
     *                   GROUP BY users.city_id
     *                  ) q0
     *            ON  (q0.m = users."name");
     *
     * -- select
     * SELECT users.id, users."name", users.city_id, users.flags, q0.m
     *   FROM users
     *      INNER JOIN (SELECT users.city_id,
     *                         MAX(users."name") m
     *                    FROM users
     *                   GROUP BY users.city_id
     *                 ) q0
     *            ON  (q0.m = users."name");
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `join subquery 02`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, users, _ ->
            val expAlias = users.name.max().alias("m")

            val query = Join(users).joinQuery(on = { it[expAlias] eq users.name }) {
                users.select(users.cityId, expAlias).groupBy(users.cityId)
            }

            val innerExp = query.lastQueryAlias!![expAlias]

            query.lastQueryAlias?.alias shouldBeEqualTo "q0"
            query.selectAll().count() shouldBeEqualTo 3
            query.select(users.columns + innerExp).first()[innerExp].shouldNotBeNull()
        }
    }

    /**
     * Postgres:
     * ```sql
     * -- firstJoinQuery
     * SELECT COUNT(*)
     *   FROM cities LEFT JOIN users ON cities.city_id = users.city_id
     *               INNER JOIN (SELECT userdata.user_id,
     *                                  userdata."comment",
     *                                  userdata."value"
     *                             FROM userdata
     *                          ) q0
     *                     ON  (q0.user_id = users.id);
     *
     * SELECT cities.city_id,
     *        cities."name",
     *        users.id,
     *        users."name",
     *        users.city_id,
     *        users.flags,
     *        q0.user_id,
     *        q0."comment",
     *        q0."value"
     *   FROM cities LEFT JOIN users ON cities.city_id = users.city_id
     *               INNER JOIN (SELECT userdata.user_id,
     *                                  userdata."comment",
     *                                  userdata."value"
     *                             FROM userdata
     *                          ) q0
     *                     ON  (q0.user_id = users.id);
     * ```
     *
     * ```sql
     * -- secondJoinQuery
     * SELECT COUNT(*)
     *   FROM cities LEFT JOIN users ON cities.city_id = users.city_id
     *               INNER JOIN (SELECT userdata.user_id,
     *                                  userdata."comment",
     *                                  userdata."value"
     *                             FROM userdata) q0
     *                     ON  (q0.user_id = users.id)
     *               INNER JOIN (SELECT userdata.user_id,
     *                                  userdata."comment",
     *                                  userdata."value"
     *                             FROM userdata) q1
     *                     ON  (q1.user_id = users.id)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `join subquery 03`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, users, userData ->
            val firstJoinQuery = cities.leftJoin(users)
                .joinQuery(
                    on = { it[userData.userId] eq users.id },
                    joinPart = { userData.selectAll() }
                )

            firstJoinQuery.lastQueryAlias?.alias shouldBeEqualTo "q0"

            firstJoinQuery.selectAll().count().toInt() shouldBeEqualTo 2
            firstJoinQuery.selectAll().forEach {
                log.debug { "userid=${it[users.id]}" }
            }

            val secondJoinQuery = firstJoinQuery
                .joinQuery(
                    on = { it[userData.userId] eq users.id },
                    joinPart = { userData.selectAll() }
                )

            secondJoinQuery.lastQueryAlias?.alias shouldBeEqualTo "q1"

            secondJoinQuery.selectAll().count().toInt() shouldBeEqualTo 2
        }
    }

    /**
     * Postgres:
     * ```sql
     * SELECT xAlias.id, xAlias.b1, xAlias.b2, xAlias.y1
     *   FROM xtable xAlias
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `wrapRow with aliased table`(testDB: TestDB) {
        withTables(testDB, XTable, YTable) {
            val entity1 = XEntity.new {
                b1 = false
            }
            flushCache()
            entityCache.clear()

            val alias = XTable.alias("xAlias")
            val entityFromAlias = alias.selectAll()
                .map { XEntity.wrapRow(it, alias) }  // alias 를 이용한 wrapRow 에는 alias 를 전달해야 한다.
                .singleOrNull()

            entityFromAlias.shouldNotBeNull()
            entityFromAlias.id shouldBeEqualTo entity1.id
            entityFromAlias.b1.shouldBeFalse()
        }
    }

    /**
     * ```sql
     * SELECT xAlias.id,
     *        xAlias.b1,
     *        xAlias.b2,
     *        xAlias.y1
     *   FROM (SELECT xtable.id,
     *                xtable.b1,
     *                xtable.b2,
     *                xtable.y1
     *           FROM xtable
     *        ) xAlias
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `wrapRow with aliased query`(testDB: TestDB) {
        withTables(testDB, XTable, YTable) {
            val entity1 = XEntity.new {
                b1 = false
            }
            flushCache()
            entityCache.clear()

            val alias = XTable.selectAll().alias("xAlias")
            val entityFromAlias = alias.selectAll()
                .map { XEntity.wrapRow(it, alias) }             // alias 를 이용한 wrapRow 에는 alias 를 전달해야 한다.
                .singleOrNull()

            entityFromAlias.shouldNotBeNull()
            entityFromAlias.id shouldBeEqualTo entity1.id
            entityFromAlias.b1.shouldBeFalse()
        }
    }

    /**
     * ```sql
     * SELECT maxBoolean.b1, maxBoolean.maxId
     *   FROM (SELECT xtable.b1,
     *                MAX(xtable.id) maxId
     *           FROM xtable
     *          GROUP BY xtable.b1
     *        ) maxBoolean
     *   LEFT JOIN xtable ON maxBoolean.maxId = xtable.id
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `aliased expression with aliased query`(testDB: TestDB) {
        withTables(testDB, XTable, YTable) {
            val dataToInsert = listOf(true, true, false, true)
            XTable.batchInsert(dataToInsert) {
                this[XTable.b1] = it
            }

            val aliasedExpr = XTable.id.max().alias("maxId")
            val aliasedQuery = XTable
                .select(XTable.b1, aliasedExpr)
                .groupBy(XTable.b1)
                .alias("maxBoolean")

            val aliasedBool = aliasedQuery[XTable.b1]
            val exprToCheck = aliasedQuery[aliasedExpr]
            exprToCheck.toString() shouldBeEqualTo "maxBoolean.maxId"

            val resultQuery = aliasedQuery
                .leftJoin(XTable, { this[aliasedExpr] }, { id })
                .select(aliasedBool, exprToCheck)

            val result = resultQuery.map {
                it[aliasedBool] to it[exprToCheck]!!.value
            }

            result shouldContainSame listOf(true to 4, false to 3)  // true를 가진 max id 는 4, false를 가진 max id 는 3
        }
    }

    /**
     * ```sql
     * SELECT t1.t1max
     *   FROM (SELECT MAX(xtable.id) t1max
     *           FROM xtable
     *          GROUP BY xtable.b1
     *        ) t1
     *   INNER JOIN (SELECT MAX(xtable.id) t2max
     *                 FROM xtable
     *                GROUP BY xtable.b1
     *               ) t2
     *         ON  (t1.t1max = t2.t2max)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `alias for same table with join`(testDB: TestDB) {
        withTables(testDB, XTable, YTable) {
            val dataToInsert = listOf(true, true, false, true)
            XTable.batchInsert(dataToInsert) {
                this[XTable.b1] = it
            }

            val table1Count = XTable.id.max().alias("t1max")
            val table2Count = XTable.id.max().alias("t2max")

            val t1Alias = XTable.select(table1Count).groupBy(XTable.b1).alias("t1")
            val t2Alias = XTable.select(table2Count).groupBy(XTable.b1).alias("t2")

            val query = t1Alias
                .join(t2Alias, JoinType.INNER) {
                    t1Alias[table1Count] eq t2Alias[table2Count]
                }
                .select(t1Alias[table1Count])

            query.forEach {
                log.debug { "maxId: ${it[t1Alias[table1Count]]}" } // 3, 4
            }
            val result = query.map { it[t1Alias[table1Count]]!!.value }
            result shouldContainSame listOf(3, 4)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `client default is same in alias`(testDB: TestDB) {
        val tester = object: IntIdTable("tester") {
            val text = text("text").clientDefault { "DEFAULT_TEXT" }
        }

        val aliasTester = tester.alias("alias_tester")

        val default = tester.columns.find { it.name == "text" }?.defaultValueFun?.invoke()
        val aliasDefault = aliasTester.columns.find { it.name == "text" }?.defaultValueFun?.invoke()

        log.debug { "aliasDefault: $aliasDefault" }
        aliasDefault shouldBeEqualTo default
        aliasDefault shouldBeEqualTo "DEFAULT_TEXT"
        default shouldBeEqualTo "DEFAULT_TEXT"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `default expression is same in alias`(testDB: TestDB) {
        val defaultExpr = stringLiteral("DEFAULT_TEXT")

        val tester = object: IntIdTable("tester") {
            val text = text("text").defaultExpression(defaultExpr)
        }

        val aliasTester = tester.alias("alias_tester")

        val default = tester.columns.find { it.name == "text" }?.defaultValueInDb()
        val aliasDefault = aliasTester.columns.find { it.name == "text" }?.defaultValueInDb()

        log.debug { "aliasDefault: $aliasDefault" }
        aliasDefault shouldBeEqualTo default
        aliasDefault shouldBeEqualTo stringLiteral("DEFAULT_TEXT")
        default shouldBeEqualTo stringLiteral("DEFAULT_TEXT")
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `database generated is same in alias`(testDB: TestDB) {
        val tester = object: IntIdTable("tester") {
            val text = text("text").databaseGenerated()
        }

        val aliasTester = tester.alias("alias_tester")

        val generated = tester.columns.find { it.name == "text" }?.isDatabaseGenerated()
        val aliasGenerated = aliasTester.columns.find { it.name == "text" }?.isDatabaseGenerated()

        log.debug { "aliasGenerated: $aliasGenerated" }  // true
        aliasGenerated shouldBeEqualTo generated
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS stables (
     *      id uuid PRIMARY KEY
     * );
     *
     * CREATE TABLE IF NOT EXISTS facilities (
     *      id uuid PRIMARY KEY,
     *      stable_id uuid NOT NULL,
     *
     *      CONSTRAINT fk_facilities_stable_id__id FOREIGN KEY (stable_id)
     *      REFERENCES stables(id) ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `reference is same in alias`(testDB: TestDB) {
        val stables = object: UUIDTable("Stables") {}
        val facilities = object: UUIDTable("Facilities") {
            val stableId = reference("stable_id", stables)
        }

        withTables(testDB, stables, facilities) {
            val facilitiesAlias = facilities.alias("FacilitiesAlias")
            val foreignKey = facilities.columns.find { it.name == "stable_id" }?.foreignKey
            val aliasForeignKey = facilitiesAlias.columns.find { it.name == "stable_id" }?.foreignKey

            aliasForeignKey shouldBeEqualTo foreignKey
        }
    }

    /**
     * Postgres:
     * ```sql
     * SELECT counter.id, counter.amount
     *   FROM tester counter
     *  WHERE (counter.id IS NULL) OR (counter.id <> 1);
     *
     * SELECT counter.id, counter.amount
     *   FROM tester counter
     *  WHERE (counter.id IS NOT NULL) AND (counter.id = 1);
     *
     * SELECT counter.id, counter.amount
     *   FROM tester counter
     *  WHERE (counter.id = 1) OR (counter.id <> 123);
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `isNull and Eq with aliased IdTable`(testDB: TestDB) {
        val tester = object: IntIdTable("tester") {
            val amount = integer("amount")
        }

        withTables(testDB, tester) {
            val t1 = tester.insertAndGetId { it[amount] = 99 }

            val counter = tester.alias("counter")

            counter
                .selectAll()
                .where { counter[tester.id].isNull() }
                .orWhere { counter[tester.id] neq t1 }
                .toList()
                .shouldBeEmpty()

            counter
                .selectAll()
                .where { counter[tester.id].isNotNull() }
                .andWhere { counter[tester.id] eq t1 }
                .single()[counter[tester.amount]] shouldBeEqualTo 99

            counter
                .selectAll()
                .where { counter[tester.id] eq t1.value }
                .orWhere { counter[tester.id] neq 123 }
                .single()[counter[tester.amount]] shouldBeEqualTo 99
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS tester1 (
     *      id BIGSERIAL PRIMARY KEY,
     *      foo VARCHAR(255) NOT NULL
     * );
     * CREATE TABLE IF NOT EXISTS tester2 (
     *      id BIGSERIAL PRIMARY KEY,
     *      "ref" BIGINT NOT NULL
     * );
     *
     *
     * SELECT tester2.id,
     *        tester2."ref",
     *        internalQuery.idAlias,
     *        internalQuery.fooAlias
     *   FROM tester2
     *      INNER JOIN (SELECT tester1.id idAlias,
     *                         tester1.foo fooAlias
     *                    FROM tester1
     *                   WHERE tester1.foo = 'foo'
     *                 ) internalQuery
     *            ON tester2."ref" = internalQuery.idAlias
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `alias from interanl query`(testDB: TestDB) {
        val tester1 = object: LongIdTable("tester1") {
            val foo = varchar("foo", 255)
        }
        val tester2 = object: LongIdTable("tester2") {
            val ref = long("ref")
        }

        withTables(testDB, tester1, tester2) {
            val id = tester1.insertAndGetId { it[foo] = "foo" }
            tester2.insert { it[ref] = id.value }

            val idAlias = tester1.id.alias("idAlias")
            val fooAlias = tester1.foo.alias("fooAlias")

            val internalQuery = tester1
                .select(idAlias, fooAlias)
                .where { tester1.foo eq "foo" }
                .alias("internalQuery")

            val query = tester2
                .innerJoin(internalQuery, { tester2.ref }, { internalQuery[idAlias] })
                .selectAll()

            query.first()[internalQuery[fooAlias]] shouldBeEqualTo "foo"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `expression with column type alias`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS subinvoices (
         *      product_id BIGINT NOT NULL,
         *      main_amount DECIMAL(4, 2) NOT NULL,
         *      is_draft BOOLEAN NOT NULL
         * );
         * ```
         */
        val subInvoices = object: Table("SubInvoices") {
            val productId = long("product_id")
            val mainAmount = decimal("main_amount", 4, 2)
            val isDraft = bool("is_draft")
        }

        withTables(testDB, subInvoices) {
            subInvoices.insert {
                it[productId] = 1
                it[mainAmount] = 3.5.toBigDecimal()
                it[isDraft] = false
            }

            val inputSum = SqlExpressionBuilder.coalesce(
                subInvoices.mainAmount.sum(), decimalLiteral(BigDecimal.ZERO)
            ).alias("input_sum")

            val input = subInvoices.select(subInvoices.productId, inputSum)
                .where {
                    subInvoices.isDraft eq false
                }.groupBy(subInvoices.productId).alias("input")

            val sumTotal = Expression.build {
                coalesce(input[inputSum], decimalLiteral(BigDecimal.ZERO))
            }.alias("inventory")

            val booleanValue = "FALSE"

            val expectedQuery = "SELECT COALESCE(input.input_sum, 0) inventory FROM " +
                    """(SELECT ${subInvoices.nameInDatabaseCase()}.${subInvoices.productId.nameInDatabaseCase()}, """ +
                    """COALESCE(SUM(${subInvoices.nameInDatabaseCase()}.${subInvoices.mainAmount.nameInDatabaseCase()}), 0) input_sum """ +
                    """FROM ${subInvoices.nameInDatabaseCase()} """ +
                    """WHERE ${subInvoices.nameInDatabaseCase()}.${subInvoices.isDraft.nameInDatabaseCase()} = $booleanValue """ +
                    """GROUP BY ${subInvoices.nameInDatabaseCase()}.${subInvoices.productId.nameInDatabaseCase()}) input"""

            log.debug { "expectedQuery=\n$expectedQuery" }

            input.select(sumTotal).prepareSQL(QueryBuilder(false)) shouldBeEqualTo expectedQuery
        }
    }
}
