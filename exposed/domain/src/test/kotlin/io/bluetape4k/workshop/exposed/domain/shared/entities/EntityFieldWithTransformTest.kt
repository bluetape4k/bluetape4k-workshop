package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Op.TRUE
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

class EntityFieldWithTransformTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TRNS (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "value" VARCHAR(50) NOT NULL
     * )
     * ```
     */
    object TransTable: IntIdTable("TRNS") {
        val value = varchar("value", 50)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS NULL_TRNS (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "value" VARCHAR(50) NULL
     * );
     * ```
     */
    object NullableTransTable: IntIdTable("NULL_TRNS") {
        val value = varchar("value", 50).nullable()
    }

    /**
     * Not Null 컬럼에 대해 transform 을 적용한다.
     */
    class TransEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TransEntity>(TransTable)

        var value by TransTable.value.transform(
            unwrap = { "transformed-$it" },
            wrap = { it.replace("transformed-", "") }
        )

        override fun equals(other: Any?): Boolean = other is TransEntity && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "TransEntity(id=$idValue, value=$value)"
    }

    /**
     * Nullable 컬럼에 대해 transform 을 적용한다.
     */
    class NullableTransEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<NullableTransEntity>(NullableTransTable)

        var value by NullableTransTable.value.transform(
            unwrap = { it?.run { "transformed-$it" } },
            wrap = { it?.replace("transformed-", "") }
        )

        override fun equals(other: Any?): Boolean = other is NullableTransEntity && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "NullableTransEntity(id=$idValue, value=$value)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `set and get value`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TransTable) {
            val entity = TransEntity.new {
                value = "stuff"
            }

            entity.value shouldBeEqualTo "stuff"

            val row = TransTable.selectAll()
                .where(TRUE)
                .first()

            row[TransTable.value] shouldBeEqualTo "transformed-stuff"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `set and get nullable value while present`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, NullableTransTable) {
            val entity = NullableTransEntity.new {
                value = "stuff"
            }

            entity.value shouldBeEqualTo "stuff"

            val row = NullableTransTable.selectAll()
                .where(TRUE)
                .first()

            row[NullableTransTable.value] shouldBeEqualTo "transformed-stuff"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `set and get nullable value while absent`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, NullableTransTable) {
            val entity = NullableTransEntity.new {
                value = null
            }

            entity.value.shouldBeNull()

            val row = NullableTransTable.selectAll()
                .where(TRUE)
                .first()

            row[NullableTransTable.value].shouldBeNull()
        }
    }

    object TableWithTransformss: IntIdTable() {
        val value = varchar("value", 50)
            .transform(
                wrap = { it.toBigDecimal() },
                unwrap = { it.toString() },
            )
    }

    class TableWithTransform(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TableWithTransform>(TableWithTransformss)

        var value: Int by TableWithTransformss.value
            .transform(
                wrap = { it.toInt() },
                unwrap = { it.toBigDecimal() },
            )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Dao transfrom with DSL transform`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TableWithTransformss) {
            TableWithTransform.new {
                value = 10
            }

            // Correct DAO value
            TableWithTransform.all().first().value shouldBeEqualTo 10

            // Correct DSL value
            TableWithTransformss.selectAll().first()[TableWithTransformss.value] shouldBeEqualTo 10.toBigDecimal()
        }
    }

    class ChainedTrans(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<ChainedTrans>(TransTable)

        var value by TransTable.value
            .transform(
                unwrap = { "transformed-$it" },
                wrap = { it.replace("transformed-", "") }
            )
            .transform(
                unwrap = { if (it.length > 5) it.slice(0..4) else it },
                wrap = { it }
            )

        override fun equals(other: Any?): Boolean = other is ChainedTrans && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "ChainedTrans(id=$idValue, value=$value)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `chained transformation`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TransTable) {
            ChainedTrans.new {
                value = "qwertyuiop"
            }

            ChainedTrans.all().first().value shouldBeEqualTo "qwert"
        }
    }

    /**
     * `memoizedTransform` 은 한 번 변환된 값을 캐싱하여 재사용한다.
     */
    class MemoizedChainedTrans(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<MemoizedChainedTrans>(TransTable)

        var value by TransTable.value
            .transform(
                unwrap = { "transformed-$it" },                                              // 2 - INSERT
                wrap = { it.replace("transformed-", "") }                   // 3 - SELECT
            )
            .memoizedTransform(
                unwrap = { it + Random.nextInt(0, 100) },                         // 1 - INSERT
                wrap = { it }                                                                // 4 - SELECT
            )

        override fun equals(other: Any?): Boolean = other is MemoizedChainedTrans && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "MemoizedChainedTrans(id=$idValue, value=$value)"
    }

    /**
     * `memoizedTransform` 은 한 번 변환된 값을 캐싱하여 재사용한다.
     * 이 경우, MemoizedChainedTrans 는 Memoized Unwrapping 을 한 번만 출력해야 한다.
     *
     * ```sql
     * INSERT INTO TRNS ("value") VALUES ('transformed-value#36')
     * ```
     *
     * ```sql
     * SELECT TRNS.ID, TRNS."value" FROM TRNS
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `memoized chained transformation`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TransTable) {
            MemoizedChainedTrans.new {
                value = "value#"
            }

            val entity = MemoizedChainedTrans.all().first()

            val firstRead = entity.value
            log.debug { "entity.value: $firstRead" }

            firstRead.startsWith("value#").shouldBeTrue()

            // cache 된 값을 재사용한다.
            entity.value shouldBeEqualTo firstRead
        }
    }
}
