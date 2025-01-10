package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EntityFieldWithTransformTest: AbstractExposedTest() {

    companion object: KLogging()

    object TransformationsTable: IntIdTable() {
        val value = varchar("value", 50)
    }

    object NullableTransformationTable: IntIdTable() {
        val value = varchar("nullable", 50).nullable()
    }

    class TransformationsEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TransformationsEntity>(TransformationsTable)

        var value by TransformationsTable.value.transform(
            unwrap = { "transformed-$it" },
            wrap = { it.replace("transformed-", "") }
        )
    }

    class NullableTransformationsEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<NullableTransformationsEntity>(NullableTransformationTable)

        var value by NullableTransformationTable.value.transform(
            unwrap = { it?.run { "transformed-$it" } },
            wrap = { it?.replace("transformed-", "") }
        )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `set and get value`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TransformationsTable) {
            val entity = TransformationsEntity.new {
                value = "stuff"
            }

            entity.value shouldBeEqualTo "stuff"

            val row = TransformationsTable.selectAll()
                .where(Op.TRUE)
                .first()

            row[TransformationsTable.value] shouldBeEqualTo "transformed-stuff"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `set and get nullable value while present`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, NullableTransformationTable) {
            val entity = NullableTransformationsEntity.new {
                value = "stuff"
            }

            entity.value shouldBeEqualTo "stuff"

            val row = NullableTransformationTable.selectAll()
                .where(Op.TRUE)
                .first()

            row[NullableTransformationTable.value] shouldBeEqualTo "transformed-stuff"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `set and get nullable value while absent`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, NullableTransformationTable) {
            val entity = NullableTransformationsEntity.new {
                value = null
            }

            entity.value.shouldBeNull()

            val row = NullableTransformationTable.selectAll()
                .where(Op.TRUE)
                .first()

            row[NullableTransformationTable.value].shouldBeNull()
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

    class ChainedTransformantionEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<ChainedTransformantionEntity>(TransformationsTable)

        var value by TransformationsTable.value
            .transform(
                unwrap = { "transformed-$it" },
                wrap = { it.replace("transformed-", "") }
            )
            .transform(
                unwrap = { if (it.length > 5) it.slice(0..4) else it },
                wrap = { it }
            )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `chained transformation`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TransformationsTable) {
            ChainedTransformantionEntity.new {
                value = "qwertyuiop"
            }

            ChainedTransformantionEntity.all().first().value shouldBeEqualTo "qwert"
        }
    }

    /**
     * memoizedTransform 은 한 번 변환된 값을 캐싱하여 재사용한다.
     */
    class MemoizedChainedTransformationEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<MemoizedChainedTransformationEntity>(TransformationsTable)

        var value by TransformationsTable.value
            .transform(
                unwrap = { "transformed-$it" },                                              // 2 - INSERT
                wrap = { it.replace("transformed-", "") }                   // 3 - SELECT
            )
            .memoizedTransform(
                unwrap = { it + kotlin.random.Random(10).nextInt(0, 100) },  // 1 - INSERT
                wrap = { it }                                                                // 4 - SELECT
            )
    }

    /**
     * memoizedTransform 은 한 번 변환된 값을 캐싱하여 재사용한다.
     * 이 경우, MemoizedChainedTransformationEntity 는 Memoized Unwrapping 을 한 번만 출력해야 한다.
     *
     * ```sql
     * INSERT INTO TRANSFORMATIONS ("value") VALUES ('transformed-value#36')
     * ```
     *
     * ```sql
     * SELECT TRANSFORMATIONS.ID, TRANSFORMATIONS."value" FROM TRANSFORMATIONS
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `memoized chained transformation`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TransformationsTable) {
            MemoizedChainedTransformationEntity.new {
                value = "value#"
            }

            val entity = MemoizedChainedTransformationEntity.all().first()

            val firstRead = entity.value
            log.debug { "entity.value: $firstRead" }

            firstRead.startsWith("value#").shouldBeTrue()
            entity.value shouldBeEqualTo firstRead
        }
    }
}
