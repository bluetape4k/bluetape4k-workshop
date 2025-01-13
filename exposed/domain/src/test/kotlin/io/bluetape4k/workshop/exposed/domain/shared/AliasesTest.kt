package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.dml.withCitiesAndUsers
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.decimalLiteral
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.joinQuery
import org.jetbrains.exposed.sql.lastQueryAlias
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.sum
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class AliasesTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count alias ClassCastException`(testDB: TestDB) {
        val stables = object: UUIDTable("Stables") {
            val name = varchar("name", 256).uniqueIndex()
        }

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
             * SELECT STABLES.ID,
             *        STABLES."name",
             *        f.fc
             *   FROM STABLES LEFT JOIN (SELECT FACILITIES.STABLE_ID,
             *                                  COUNT(FACILITIES."name") fc
             *                             FROM FACILITIES
             *                            GROUP BY FACILITIES.STABLE_ID) f ON STABLES.ID = f.STABLE_ID
             *  GROUP BY STABLES.ID, STABLES."name", f.fc
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
     * ```sql
     * SELECT USERS.ID,
     *        USERS."name",
     *        USERS.CITY_ID,
     *        USERS.FLAGS,
     *        u2.CITY_ID,
     *        u2.m
     *   FROM USERS INNER JOIN
     *          (
     *              SELECT USERS.CITY_ID,
     *                     MAX(USERS."name") m
     *                FROM USERS
     *               GROUP BY USERS.CITY_ID
     *           ) u2
     *         ON u2.m = USERS."name"
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
     * Count
     * ```sql
     * SELECT COUNT(*)
     *   FROM USERS INNER JOIN (
     *          SELECT USERS.CITY_ID,
     *                 MAX(USERS."name") m
     *            FROM USERS
     *           GROUP BY USERS.CITY_ID
     *          ) q0 ON  (q0.m = USERS."name")
     * ```
     *
     * ```sql
     * SELECT USERS.ID,
     *        USERS."name",
     *        USERS.CITY_ID,
     *        USERS.FLAGS,
     *        q0.m
     *   FROM USERS INNER JOIN (
     *          SELECT USERS.CITY_ID,
     *                 MAX(USERS."name") m
     *            FROM USERS
     *           GROUP BY USERS.CITY_ID
     *        ) q0 ON  (q0.m = USERS."name")
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
     * firstJoinQuery
     * ```sql
     * SELECT COUNT(*)
     *   FROM CITIES LEFT JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *               INNER JOIN (SELECT USERDATA.USER_ID,
     *                                  USERDATA.COMMENT,
     *                                  USERDATA."value"
     *                             FROM USERDATA) q0 ON  (q0.USER_ID = USERS.ID)
     * ```
     *
     * secondJoinQuery
     * ```sql
     * SELECT COUNT(*)
     *   FROM CITIES LEFT JOIN USERS ON CITIES.CITY_ID = USERS.CITY_ID
     *               INNER JOIN (SELECT USERDATA.USER_ID,
     *                                  USERDATA.COMMENT,
     *                                  USERDATA."value"
     *                             FROM USERDATA) q0 ON  (q0.USER_ID = USERS.ID)
     *               INNER JOIN (SELECT USERDATA.USER_ID,
     *                                  USERDATA.COMMENT,
     *                                  USERDATA."value"
     *                             FROM USERDATA) q1 ON  (q1.USER_ID = USERS.ID)
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
     * ```sql
     * SELECT xAlias.ID, xAlias.B1, xAlias.B2, xAlias.Y1
     *   FROM XTABLE xAlias
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `wrapRow with aliased table`(testDB: TestDB) {
        withTables(testDB, EntityTestData.XTable, EntityTestData.YTable) {
            val entity1 = EntityTestData.XEntity.new {
                b1 = false
            }
            flushCache()
            entityCache.clear()

            val alias = EntityTestData.XTable.alias("xAlias")
            val entityFromAlias = alias.selectAll()
                .map { EntityTestData.XEntity.wrapRow(it, alias) }  // alias 를 이용한 wrapRow 에는 alias 를 전달해야 한다.
                .singleOrNull()

            entityFromAlias.shouldNotBeNull()
            entityFromAlias.id shouldBeEqualTo entity1.id
            entityFromAlias.b1.shouldBeFalse()
        }
    }

    /**
     * ```sql
     * SELECT xAlias.ID, xAlias.B1, xAlias.B2, xAlias.Y1
     *   FROM (
     *      SELECT XTABLE.ID, XTABLE.B1, XTABLE.B2, XTABLE.Y1
     *        FROM XTABLE
     *   ) xAlias
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `wrapRow with aliased query`(testDB: TestDB) {
        withTables(testDB, EntityTestData.XTable, EntityTestData.YTable) {
            val entity1 = EntityTestData.XEntity.new {
                b1 = false
            }
            flushCache()
            entityCache.clear()

            val alias = EntityTestData.XTable.selectAll().alias("xAlias")
            val entityFromAlias = alias.selectAll()
                .map { EntityTestData.XEntity.wrapRow(it, alias) }  // alias 를 이용한 wrapRow 에는 alias 를 전달해야 한다.
                .singleOrNull()

            entityFromAlias.shouldNotBeNull()
            entityFromAlias.id shouldBeEqualTo entity1.id
            entityFromAlias.b1.shouldBeFalse()
        }
    }

    /**
     * ```sql
     * SELECT maxBoolean.B1,
     *        maxBoolean.maxId
     *  FROM (SELECT XTABLE.B1, MAX(XTABLE.ID) maxId
     *          FROM XTABLE
     *         GROUP BY XTABLE.B1
     *       ) maxBoolean LEFT JOIN XTABLE ON maxBoolean.maxId = XTABLE.ID
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `aliased expression with aliased query`(testDB: TestDB) {
        withTables(testDB, EntityTestData.XTable, EntityTestData.YTable) {
            val dataToInsert = listOf(true, true, false, true)
            EntityTestData.XTable.batchInsert(dataToInsert) {
                this[EntityTestData.XTable.b1] = it
            }

            val aliasedExpr = EntityTestData.XTable.id.max().alias("maxId")
            val aliasedQuery = EntityTestData.XTable
                .select(EntityTestData.XTable.b1, aliasedExpr)
                .groupBy(EntityTestData.XTable.b1)
                .alias("maxBoolean")

            val aliasedBool = aliasedQuery[EntityTestData.XTable.b1]
            val exprToCheck = aliasedQuery[aliasedExpr]
            exprToCheck.toString() shouldBeEqualTo "maxBoolean.maxId"

            val resultQuery = aliasedQuery
                .leftJoin(EntityTestData.XTable, { this[aliasedExpr] }, { id })
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
     *   FROM (SELECT MAX(XTABLE.ID) t1max FROM XTABLE GROUP BY XTABLE.B1) t1
     *   INNER JOIN (SELECT MAX(XTABLE.ID) t2max FROM XTABLE GROUP BY XTABLE.B1) t2
     *   ON  (t1.t1max = t2.t2max)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `alias for same table with join`(testDB: TestDB) {
        withTables(testDB, EntityTestData.XTable, EntityTestData.YTable) {
            val dataToInsert = listOf(true, true, false, true)
            EntityTestData.XTable.batchInsert(dataToInsert) {
                this[EntityTestData.XTable.b1] = it
            }

            val table1Count = EntityTestData.XTable.id.max().alias("t1max")
            val table2Count = EntityTestData.XTable.id.max().alias("t2max")

            val t1Alias = EntityTestData.XTable.select(table1Count).groupBy(EntityTestData.XTable.b1).alias("t1")
            val t2Alias = EntityTestData.XTable.select(table2Count).groupBy(EntityTestData.XTable.b1).alias("t2")

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
     * ```sql
     * SELECT counter.ID, counter.AMOUNT
     *   FROM TESTER counter
     *  WHERE (counter.ID IS NULL) OR (counter.ID <> 1)
     * ```
     * ```sql
     * SELECT counter.ID, counter.AMOUNT
     *   FROM TESTER counter
     *  WHERE (counter.ID IS NOT NULL) AND (counter.ID = 1)
     * ```
     * ```sql
     * SELECT counter.ID, counter.AMOUNT
     *   FROM TESTER counter
     *  WHERE (counter.ID = 1) OR (counter.ID <> 123)
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

            val result1 = counter.selectAll()
                .where {
                    counter[tester.id].isNull() or (counter[tester.id] neq t1)
                }
                .toList()
            result1.shouldBeEmpty()

            val result2 = counter.selectAll()
                .where {
                    counter[tester.id].isNotNull() and (counter[tester.id] eq t1)
                }
                .single()

            result2[counter[tester.amount]] shouldBeEqualTo 99

            val result3 = counter.selectAll()
                .where {
                    (counter[tester.id] eq t1.value) or (counter[tester.id] neq 123)
                }
                .single()
            result3[counter[tester.amount]] shouldBeEqualTo 99
        }
    }

    /**
     * ```sql
     * SELECT TESTER2.ID,
     *        TESTER2."ref",
     *        internalQuery.idAlias,
     *        internalQuery.fooAlias
     *   FROM TESTER2
     *      INNER JOIN (
     *              SELECT TESTER1.ID idAlias,
     *                     TESTER1.FOO fooAlias
     *                FROM TESTER1
     *               WHERE TESTER1.FOO = 'foo'
     *         ) internalQuery
     *      ON TESTER2."ref" = internalQuery.idAlias
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
                .innerJoin(internalQuery, { ref }, { internalQuery[idAlias] })
                .selectAll()

            query.first()[internalQuery[fooAlias]] shouldBeEqualTo "foo"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `expression with column type alias`(testDB: TestDB) {
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

            input.select(sumTotal).prepareSQL(QueryBuilder(false)) shouldBeEqualTo expectedQuery
        }
    }
}
