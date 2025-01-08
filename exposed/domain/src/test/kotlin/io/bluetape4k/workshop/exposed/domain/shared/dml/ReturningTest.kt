package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteReturning
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertReturning
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.ReturningStatement
import org.jetbrains.exposed.sql.updateReturning
import org.jetbrains.exposed.sql.upsertReturning
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * * UPDATE ... RETURNING 은 Postgres, SQLite 에서만 지원
 *
 * * INSERT INTO ... RETURNING  구문은 Postgres, MariaDB 에서만 지원
 */
class ReturningTest: AbstractExposedTest() {

    private val updateReturningSupportedDb = TestDB.ALL_POSTGRES
    private val returningSupportedDb = updateReturningSupportedDb

    object Items: IntIdTable("items") {
        val name = varchar("name", 32)
        val price = double("price")
    }

    class ItemDAO(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<ItemDAO>(Items)

        var name by Items.name
        var price by Items.price
    }

    /**
     * Insert Returning
     *
     * Postgres:
     * ```sql
     * INSERT INTO items ("name", price)
     * VALUES ('A', 99.0)
     * RETURNING items.id, items."name", items.price
     * ```
     *
     * ```sql
     * INSERT INTO items ("name", price)
     * VALUES ('B', 200.0)
     * RETURNING items.id, items."name"
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert returning`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in returningSupportedDb }

        withTables(testDb, Items) {
            // return all columns by default
            val result1 = Items.insertReturning {
                it[name] = "A"
                it[price] = 99.0
            }.single()

            result1[Items.id].value shouldBeEqualTo 1
            result1[Items.name] shouldBeEqualTo "A"
            result1[Items.price] shouldBeEqualTo 99.0

            val result2 = Items.insertReturning(listOf(Items.id, Items.name)) {
                it[name] = "B"
                it[price] = 200.0
            }.single()

            result2[Items.id].value shouldBeEqualTo 2
            result2[Items.name] shouldBeEqualTo "B"
            assertFailsWith<IllegalStateException> { // Items.price not in record set
                result2[Items.price]
            }

            Items.selectAll().count().toInt() shouldBeEqualTo 2
        }
    }

    /**
     *
     * ```
     * INSERT INTO tester (item) VALUES ('Item A')
     * ```
     *
     * Unique index 위배로 insert 무시 (ignoreErrors = true)
     * ```sql
     * INSERT INTO tester (item) VALUES ('Item A') ON CONFLICT DO NOTHING RETURNING tester.item
     * ```
     *
     * 새로운 item 추가
     * ```sql
     * INSERT INTO tester (item) VALUES ('Item B') ON CONFLICT DO NOTHING RETURNING tester.item
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert ignore returning`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in returningSupportedDb }

        val tester = object: Table("tester") {
            val item = varchar("item", 21).uniqueIndex()
        }

        withTables(testDb, tester) {
            tester.insert {
                it[item] = "Item A"
            }
            tester.selectAll().count().toInt() shouldBeEqualTo 1

            // Unique index 위배로 insert 무시 (ignoreErrors = true)
            val resultWithConflict = tester.insertReturning(ignoreErrors = true) {
                it[item] = "Item A"
            }.toList()

            resultWithConflict.shouldBeEmpty()
            tester.selectAll().count().toInt() shouldBeEqualTo 1

            val resultWithoutConflict = tester.insertReturning(ignoreErrors = true) {
                it[item] = "Item B"
            }.single()

            resultWithoutConflict[tester.item] shouldBeEqualTo "Item B"
            tester.selectAll().count().toInt() shouldBeEqualTo 2
        }
    }

    /**
     * result1
     * ```sql
     * INSERT INTO items ("name", price)
     * VALUES ('A', 99.0)
     * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name", price=EXCLUDED.price
     * RETURNING items.id, items."name", items.price
     * ```
     *
     * result2:
     * ```sql
     * INSERT INTO items (id, "name", price)
     * VALUES (1, 'B', 200.0)
     * ON CONFLICT (id) DO UPDATE SET price=(items.price * 10.0)
     * RETURNING items."name", items.price
     * ```
     *
     * result3:
     * ```sql
     * INSERT INTO items (id, "name", price)
     * VALUES (1, 'B', 200.0)
     * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name" WHERE items.price > 500.0
     * RETURNING items."name"
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert returing`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in returningSupportedDb }

        withTables(testDb, Items) {
            // return all columns by default
            val result1 = Items.upsertReturning {
                it[name] = "A"
                it[price] = 99.0
            }.single()

            result1[Items.id].value shouldBeEqualTo 1
            result1[Items.name] shouldBeEqualTo "A"
            result1[Items.price] shouldBeEqualTo 99.0

            val result2 = Items.upsertReturning(
                returning = listOf(Items.name, Items.price),
                onUpdate = { it[Items.price] = Items.price * 10.0 }
            ) {
                it[Items.id] = 1
                it[Items.name] = "B"
                it[Items.price] = 200.0
            }.single()

            result2[Items.name] shouldBeEqualTo "A"
            result2[Items.price] shouldBeEqualTo 990.0


            val result3 = Items.upsertReturning(
                returning = listOf(Items.name),
                onUpdateExclude = listOf(Items.price),
                where = { Items.price greater 500.0 }
            ) {
                it[Items.id] = 1
                it[Items.name] = "B"
                it[Items.price] = 200.0
            }.single()

            result3[Items.name] shouldBeEqualTo "B"

            Items.selectAll().count().toInt() shouldBeEqualTo 1
        }
    }

    /**
     * result1:
     * ```sql
     * INSERT INTO items ("name", price) VALUES ('A', 99.0)
     * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name", price=EXCLUDED.price
     * RETURNING items.id, items."name", items.price
     * ```
     *
     * result2:
     * ```sql
     * INSERT INTO items (id, "name", price) VALUES (1, 'B', 200.0)
     * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name", price=EXCLUDED.price
     * RETURNING items.id, items."name", items.price
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert returning with DAO`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in returningSupportedDb }

        withTables(testDb, Items) {
            val result1 = Items.upsertReturning {
                it[name] = "A"
                it[price] = 99.0
            }.let {
                ItemDAO.wrapRow(it.single())
            }

            result1.id.value shouldBeEqualTo 1
            result1.name shouldBeEqualTo "A"
            result1.price shouldBeEqualTo 99.0

            val result2 = Items.upsertReturning {
                it[id] = 1
                it[name] = "B"
                it[price] = 200.0
            }.let {
                ItemDAO.wrapRow(it.single())
            }
            result2.id.value shouldBeEqualTo 1
            result2.name shouldBeEqualTo "B"
            result2.price shouldBeEqualTo 200.0

            Items.selectAll().count().toInt() shouldBeEqualTo 1
            ItemDAO.all().count().toInt() shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `returning with no results`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in returningSupportedDb }

        withTables(testDb, Items) {
            // 결과가 없다면, 구문은 실행되지 않습니다.
            val stmt = Items.insertReturning {
                it[name] = "A"
                it[price] = 99.0
            }
            assertIs<ReturningStatement>(stmt)

            Items.selectAll().count().toInt() shouldBeEqualTo 0

            // 삭제될 것이 없으므로, 결과가 없습니다.
            Items.deleteReturning().toList().shouldBeEmpty()
        }
    }

    /**
     * result1:
     * ```sql
     * DELETE FROM items WHERE items.price = 200.0
     * RETURNING items.id, items."name", items.price
     * ```
     *
     * result2:
     * ```
     * DELETE FROM items RETURNING items.id
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete returning`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in returningSupportedDb }

        withTables(testDb, Items) {
            Items.batchInsert(listOf("A" to 99.0, "B" to 100.0, "C" to 200.0)) { (n, p) ->
                this[Items.name] = n
                this[Items.price] = p
            }
            Items.selectAll().count().toInt() shouldBeEqualTo 3

            // price가 200.0인 레코드를 삭제하고, 삭제된 행을 반환합니다.
            val result1 = Items.deleteReturning(where = { Items.price eq 200.0 }).single()
            result1[Items.id].value shouldBeEqualTo 3
            result1[Items.name] shouldBeEqualTo "C"
            result1[Items.price] shouldBeEqualTo 200.0

            Items.selectAll().count().toInt() shouldBeEqualTo 2

            val result2 = Items.deleteReturning(listOf(Items.id)).map { it[Items.id].value }
            result2 shouldBeEqualTo listOf(1, 2)

            Items.selectAll().count().toInt() shouldBeEqualTo 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update returning`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in updateReturningSupportedDb }

        withTables(testDb, Items) {
            val input = listOf("A" to 99.0, "B" to 100.0, "C" to 200.0)
            Items.batchInsert(input) { (n, p) ->
                this[Items.name] = n
                this[Items.price] = p
            }

            val result1: ResultRow = Items
                .updateReturning(where = { Items.price lessEq 99.0 }) {
                    it[Items.price] = price * 10.0
                }
                .single()

            result1[Items.id].value shouldBeEqualTo 1
            result1[Items.name] shouldBeEqualTo "A"
            result1[Items.price] shouldBeEqualTo 990.0

            val result2: List<String> = Items
                .updateReturning(listOf(Items.name)) {
                    it[name] = name.lowerCase()
                }
                .map { it[Items.name] }
            result2.toSet() shouldBeEqualTo input.map { it.first.lowercase() }.toSet()

            val newPrice = Items.price.alias("new_price")
            val result3: List<Double> = Items
                .updateReturning(listOf(newPrice)) {
                    it[Items.price] = 0.0
                }
                .map { it[newPrice] }

            result3 shouldHaveSize 3
            result3.all { it == 0.0 }.shouldBeTrue()

            Items.selectAll().count().toInt() shouldBeEqualTo 3
        }
    }
}
