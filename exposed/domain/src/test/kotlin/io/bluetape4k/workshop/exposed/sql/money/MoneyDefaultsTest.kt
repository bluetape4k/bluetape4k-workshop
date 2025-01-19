package io.bluetape4k.workshop.exposed.sql.money

import io.bluetape4k.logging.KLogging
import io.bluetape4k.money.moneyOf
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
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
     * H2:
     * ```sql
     * CREATE TABLE IF NOT EXISTS TABLEWITHDBDEFAULT (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      FIELD VARCHAR(100) NOT NULL,
     *      T1 DECIMAL(10, 0) DEFAULT 1 NOT NULL,
     *      "t1_C" VARCHAR(3) DEFAULT 'USD' NOT NULL,
     *      T2 DECIMAL(10, 0) NULL,
     *      "t2_C" VARCHAR(3) NULL,
     *      "clientDefault" INT NOT NULL
     * )
     * ```
     */
    object TableWithDBDefault: IntIdTable("TableWithDBDefault") {
        val defaultValue = moneyOf(BigDecimal.ONE, "USD")

        val cIndex = AtomicInteger(0)
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

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is DBDefault &&
                    t1 == other.t1 &&
                    t2 == other.t2 &&
                    clientDefault == other.clientDefault
        }

        override fun hashCode(): Int = id.value.hashCode()
    }

    /**
     * 기본값을 명시적으로 설정한 경우를 테스트합니다.
     *
     * ```sql
     * INSERT INTO TABLEWITHDBDEFAULT (FIELD, T1, "t1_C", "clientDefault") VALUES ('1', 1, 'USD', 0)
     * INSERT INTO TABLEWITHDBDEFAULT (FIELD, T1, "t1_C", "clientDefault") VALUES ('2', 10, 'USD', 1)
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
     * H2:
     * ```sql
     * -- insert
     * INSERT INTO TABLEWITHDBDEFAULT (FIELD, T1, "t1_C", "clientDefault") VALUES ('1', 1, 'USD', 0)
     *
     * -- update
     * UPDATE TABLEWITHDBDEFAULT SET T2=10, "t2_C"='USD' WHERE TABLEWITHDBDEFAULT.ID = 1
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
