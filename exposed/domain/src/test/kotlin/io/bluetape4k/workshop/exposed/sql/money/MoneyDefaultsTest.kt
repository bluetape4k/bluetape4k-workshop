package io.bluetape4k.workshop.exposed.sql.money

import io.bluetape4k.logging.KLogging
import io.bluetape4k.money.moneyOf
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.money.compositeMoney
import org.jetbrains.exposed.sql.money.nullable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

class MoneyDefaultsTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS tablewithdbdefault (
     *      id SERIAL PRIMARY KEY,
     *      field VARCHAR(100) NOT NULL,
     *      t1 DECIMAL(10, 0) DEFAULT 1 NOT NULL,
     *      "t1_C" VARCHAR(3) DEFAULT 'USD' NOT NULL,
     *      t2 DECIMAL(10, 0) NULL,
     *      "t2_C" VARCHAR(3) NULL,
     *      "clientDefault" INT NOT NULL
     * )
     * ```
     */
    object TableWithDBDefault: IntIdTable("TableWithDBDefault") {
        internal val defaultValue = moneyOf(BigDecimal.ONE, "USD") // 컬럼이 아닙니다.
        internal val cIndex = AtomicInteger(0)  // 컬럼이 아닙니다.

        val field = varchar("field", 100)
        val t1 = compositeMoney(10, 0, "t1").default(defaultValue)
        val t2 = compositeMoney(10, 0, "t2").nullable()
        val clientDefault = integer("clientDefault").clientDefault { cIndex.getAndIncrement() }
    }

    class DBDefault(id: EntityID<Int>): IntEntity(id) {
        companion object: EntityClass<Int, DBDefault>(TableWithDBDefault)

        var field by TableWithDBDefault.field
        var t1 by TableWithDBDefault.t1
        var t2 by TableWithDBDefault.t2
        val clientDefault by TableWithDBDefault.clientDefault

        override fun equals(other: Any?): Boolean =
            other is DBDefault &&
                    t1 == other.t1 &&
                    t2 == other.t2 &&
                    clientDefault == other.clientDefault

        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String =
            "DBDefault(id=$idValue, field=$field, t1=$t1, t2=$t2, clientDefault=$clientDefault)"
    }

    /**
     * 기본값을 명시적으로 설정한 경우를 테스트합니다.
     *
     * Postgres:
     * ```sql
     * INSERT INTO tablewithdbdefault (field, t1, "t1_C", "clientDefault")
     * VALUES ('1', 1, 'USD', 7);
     *
     * -- t1 컬럼에 10 'USD' 를 설정합니다.
     * INSERT INTO tablewithdbdefault (field, t1, "t1_C", "clientDefault")
     * VALUES ('2', 10, 'USD', 8);
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults with explicit`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            val created = listOf(
                DBDefault.new { field = "1" },
                DBDefault.new {
                    field = "2"
                    t1 = moneyOf(BigDecimal.TEN, "USD")
                }
            )
            flushCache()
            created.forEach {
                DBDefault.removeFromCache(it)
            }

            val entities = DBDefault.all().toList()
            entities shouldBeEqualTo created
        }
    }

    /**
     * 엔티티별로 기본값 조회는 한번만 수행됩니다.
     *
     * ```sql
     * -- 2번 수행해도 t1 의 기본값은 1 입니다.
     *
     * INSERT INTO tablewithdbdefault (field, t1, "t1_C", "clientDefault")
     * VALUES ('1', 1, 'USD', 0);
     *
     * INSERT INTO tablewithdbdefault (field, t1, "t1_C", "clientDefault")
     * VALUES ('2', 1, 'USD', 1);
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults invoked only once per entity`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            TableWithDBDefault.cIndex.set(0)

            val db1 = DBDefault.new { field = "1" }
            val db2 = DBDefault.new { field = "2" }

            flushCache()

            db1.clientDefault shouldBeEqualTo 0
            db2.clientDefault shouldBeEqualTo 1
            TableWithDBDefault.cIndex.get() shouldBeEqualTo 2

            db1.t1 shouldBeEqualTo TableWithDBDefault.defaultValue
        }
    }

    /**
     * Postgres:
     * ```sql
     * -- insert 시 nullable 컬럼은 null 로 설정됩니다.
     * INSERT INTO tablewithdbdefault (field, t1, "t1_C", "clientDefault")
     * VALUES ('1', 1, 'USD', 0)
     *
     * -- update for nullable money column
     * UUPDATE tablewithdbdefault
     *     SET t2=10,
     *         "t2_C"='USD'
     *   WHERE tablewithdbdefault.id = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nullable composite column type`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            TableWithDBDefault.cIndex.set(0)

            val db1 = DBDefault.new { field = "1" }
            flushCache()
            db1.t2.shouldBeNull()

            val money = moneyOf(BigDecimal.TEN, "USD")
            db1.t2 = money
            db1.refresh(flush = true)

            db1.t2 shouldBeEqualTo money
            db1.t1 shouldBeEqualTo TableWithDBDefault.defaultValue
        }
    }
}
