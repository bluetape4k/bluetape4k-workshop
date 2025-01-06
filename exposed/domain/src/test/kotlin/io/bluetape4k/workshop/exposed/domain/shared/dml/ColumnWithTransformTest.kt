package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnTransformer
import org.jetbrains.exposed.sql.ColumnWithTransform
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.Serializable
import java.util.*

class ColumnWithTransformTest: AbstractExposedTest() {

    companion object: KLogging()

    @JvmInline
    value class TransformDataHolder(val value: Int): Serializable

    class DataHolderTransformer: ColumnTransformer<Int, TransformDataHolder> {
        override fun unwrap(value: TransformDataHolder): Int = value.value
        override fun wrap(value: Int): TransformDataHolder = TransformDataHolder(value)
    }

    class DataHolderNullableTransformer: ColumnTransformer<Int?, TransformDataHolder?> {
        override fun unwrap(value: TransformDataHolder?): Int? = value?.value
        override fun wrap(value: Int?): TransformDataHolder? = value?.let { TransformDataHolder(it) }
    }

    class DataHolderNullTransformer: ColumnTransformer<Int, TransformDataHolder?> {
        override fun unwrap(value: TransformDataHolder?): Int = value?.value ?: 0
        override fun wrap(value: Int): TransformDataHolder? = if (value == 0) null else TransformDataHolder(value)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `recursive unwrap`() {
        val tester1 = object: IntIdTable() {
            val value: Column<TransformDataHolder?> = integer("value")
                .transform(DataHolderTransformer())
                .nullable()
        }
        val columnType1 = tester1.value.columnType as? ColumnWithTransform<Int, TransformDataHolder>
        columnType1.shouldNotBeNull()
        columnType1.unwrapRecursive(TransformDataHolder(1)) shouldBeEqualTo 1
        columnType1.unwrapRecursive(null).shouldBeNull()

        // Transform null into non-null value
        val tester2 = object: IntIdTable() {
            val value = integer("value")
                .nullTransform(DataHolderNullTransformer())
        }
        val columnType2 = tester2.value.columnType as? ColumnWithTransform<Int, TransformDataHolder?>
        columnType2.shouldNotBeNull()
        columnType2.unwrapRecursive(TransformDataHolder(1)) shouldBeEqualTo 1
        columnType2.unwrapRecursive(null) shouldBeEqualTo 0

        // Transform 을 2번 적용하므로, ColumnWithTransform<TransformDataHolder?, Int?> 가 생성되어야 한다.
        val tester3 = object: IntIdTable() {
            val value = integer("value")
                .transform(DataHolderTransformer())
                .nullable()
                .transform(wrap = { it?.value ?: 0 }, unwrap = { TransformDataHolder(it ?: 0) })
        }
        val columnType3 = tester3.value.columnType as? ColumnWithTransform<TransformDataHolder?, Int?>
        columnType3.shouldNotBeNull()
        columnType3.unwrapRecursive(1) shouldBeEqualTo 1
        columnType3.unwrapRecursive(null) shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `simple transforms`(testDb: TestDB) {
        val tester = object: IntIdTable("simple_transforms") {
            val v1 = integer("v1")
                .transform(
                    wrap = { TransformDataHolder(it) },
                    unwrap = { it.value }
                )
            val v2 = integer("v2")
                .nullable()
                .transform(
                    wrap = { it?.let { TransformDataHolder(it) } },
                    unwrap = { it?.value }
                )
            val v3 = integer("v3")
                .transform(
                    wrap = { TransformDataHolder(it) },
                    unwrap = { it.value }
                )
                .nullable()
        }

        withTables(testDb, tester) {
            val id1 = tester.insertAndGetId {
                it[v1] = TransformDataHolder(1)
                it[v2] = TransformDataHolder(2)
                it[v3] = TransformDataHolder(3)
            }

            val entry1 = tester.selectAll().where { tester.id eq id1 }.single()
            entry1[tester.v1].value shouldBeEqualTo 1
            entry1[tester.v2]?.value shouldBeEqualTo 2
            entry1[tester.v3]?.value shouldBeEqualTo 3

            val id2 = tester.insertAndGetId {
                it[v1] = TransformDataHolder(1)
                it[v2] = null
                it[v3] = null
            }
            val entry2 = tester.selectAll().where { tester.id eq id2 }.single()
            entry2[tester.v1].value shouldBeEqualTo 1
            entry2[tester.v2].shouldBeNull()
            entry2[tester.v3].shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nested transforms`(testDb: TestDB) {
        val tester = object: IntIdTable("nested_transformer") {
            val v1: Column<String> = integer("v1")
                .transform(DataHolderTransformer())
                .transform(
                    wrap = { it.value.toString() },
                    unwrap = { TransformDataHolder(it.toInt()) }
                )

            val v2: Column<String?> = integer("v2")
                .transform(DataHolderTransformer())
                .transform(
                    wrap = { it.value.toString() },
                    unwrap = { TransformDataHolder(it.toInt()) }
                )
                .nullable()

            val v3: Column<String?> = integer("v3")
                .transform(DataHolderTransformer())
                .nullable()
                .transform(
                    wrap = { it?.value.toString() },
                    unwrap = { it?.let { it1 -> TransformDataHolder(it1.toInt()) } }
                )

            val v4: Column<String?> = integer("v4")
                .nullable()
                .transform(DataHolderNullableTransformer())
                .transform(
                    wrap = { it?.value.toString() },
                    unwrap = { it?.let { it1 -> TransformDataHolder(it1.toInt()) } }
                )
        }

        withTables(testDb, tester) {
            val id1 = tester.insertAndGetId {
                it[v1] = "1"
                it[v2] = "2"
                it[v3] = "3"
                it[v4] = "4"
            }

            val entry1 = tester.selectAll().where { tester.id eq id1 }.single()
            entry1[tester.v1] shouldBeEqualTo "1"
            entry1[tester.v2] shouldBeEqualTo "2"
            entry1[tester.v3] shouldBeEqualTo "3"
            entry1[tester.v4] shouldBeEqualTo "4"

            val id2 = tester.insertAndGetId {
                it[v1] = "1"
                it[v2] = null
                it[v3] = null
                it[v4] = null
            }
            val entry2 = tester.selectAll().where { tester.id eq id2 }.single()
            entry2[tester.v1] shouldBeEqualTo "1"
            entry2[tester.v2].shouldBeNull()
            entry2[tester.v3].shouldBeNull()
            entry2[tester.v4].shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `read transformed values from insert statement`(testDb: TestDB) {
        val tester = object: IntIdTable("read_transformed_values") {
            val v1: Column<TransformDataHolder> = integer("v1").transform(DataHolderTransformer())
            val v2: Column<TransformDataHolder?> = integer("v2").nullTransform(DataHolderNullTransformer())
        }

        withTables(testDb, tester) {
            val statement = tester.insert {
                it[tester.v1] = TransformDataHolder(1)
                it[tester.v2] = null
            }

            statement[tester.v1].value shouldBeEqualTo 1
            statement[tester.v2].shouldBeNull()
        }
    }

    object TransformTable: IntIdTable("transform_table") {
        val simple: Column<TransformDataHolder> = integer("simple")
            .default(1)
            .transform(DataHolderTransformer())
        val chained: Column<TransformDataHolder> = varchar("chained", 128)
            .transform(wrap = { it.toInt() }, unwrap = { it.toString() })
            .transform(DataHolderTransformer())
            .default(TransformDataHolder(2))
    }

    class TransformEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TransformEntity>(TransformTable)

        var simple: TransformDataHolder by TransformTable.simple
        var chained: TransformDataHolder by TransformTable.chained
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `transformed values with DAO`(testDb: TestDB) {
        withTables(testDb, TransformTable) {
            val entity = TransformEntity.new {
                simple = TransformDataHolder(120)
                chained = TransformDataHolder(240)
            }

            entity.simple shouldBeEqualTo TransformDataHolder(120)
            entity.chained shouldBeEqualTo TransformDataHolder(240)

            val row = TransformTable.selectAll().first()
            row[TransformTable.simple] shouldBeEqualTo TransformDataHolder(120)
            row[TransformTable.chained] shouldBeEqualTo TransformDataHolder(240)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `entity with default value`(testDb: TestDB) {
        withTables(testDb, TransformTable) {
            val entity = TransformEntity.new { }
            entity.simple shouldBeEqualTo TransformDataHolder(1)
            entity.chained shouldBeEqualTo TransformDataHolder(2)

            val row = TransformTable.selectAll().first()
            row[TransformTable.simple] shouldBeEqualTo TransformDataHolder(1)
            row[TransformTable.chained] shouldBeEqualTo TransformDataHolder(2)
        }
    }

    @JvmInline
    value class CustomId(val id: UUID): Serializable

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `transform id column`(testDb: TestDB) {
        val tester = object: IdTable<CustomId>() {
            override val id: Column<EntityID<CustomId>> = uuid("id")
                .transform(wrap = { CustomId(it) }, unwrap = { it.id })
                .entityId()

            override val primaryKey = PrimaryKey(id)
        }

        val referenceTester = object: IntIdTable() {
            val reference: Column<EntityID<CustomId>> = reference("reference", tester)
        }

        val uuid = TimebasedUuid.Epoch.nextId()
        withTables(testDb, tester, referenceTester) {
            tester.insert {
                it[id] = CustomId(uuid)
            }
            val transformedId: EntityID<CustomId> = tester.selectAll().single()[tester.id]
            transformedId.value shouldBeEqualTo CustomId(uuid)

            referenceTester.insert {
                it[reference] = transformedId
            }

            val referenceId = referenceTester.selectAll().single()[referenceTester.reference]
            referenceId.value shouldBeEqualTo CustomId(uuid)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `null to non-null transform`(testDb: TestDB) {
        val tester = object: IntIdTable("tester") {
            val value: Column<Int?> = integer("value")
                .nullable()
                .transform(wrap = { if (it == -1) null else it }, unwrap = { it ?: -1 })
        }
        val rawTester = object: IntIdTable("tester") {
            val value: Column<Int?> = integer("value").nullable()
        }

        withTables(testDb, tester) {
            tester.insert {
                it[value] = null
            }

            tester.selectAll().single()[tester.value] shouldBeEqualTo null
            rawTester.selectAll().single()[rawTester.value] shouldBeEqualTo -1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `null to non-null recursive transform`(testDb: TestDB) {
        val tester = object: IntIdTable("tester") {
            val value: Column<TransformDataHolder?> = integer("value")
                .nullable()
                .transform(wrap = { if (it == -1) null else it }, unwrap = { it ?: -1 })
                .transform(DataHolderNullableTransformer())
        }
        val rawTester = object: IntIdTable("tester") {
            val value: Column<Long?> = long("value").nullable()
        }

        withTables(testDb, tester) {
            val id1 = tester.insertAndGetId {
                it[value] = TransformDataHolder(100)
            }
            tester.selectAll().where { tester.id eq id1 }.single()[tester.value]?.value shouldBeEqualTo 100
            rawTester.selectAll().where { rawTester.id eq id1 }.single()[rawTester.value] shouldBeEqualTo 100L

            val id2 = tester.insertAndGetId {
                it[value] = null
            }

            tester.selectAll().where { tester.id eq id2 }.single()[tester.value]?.value.shouldBeNull()
            rawTester.selectAll().where { rawTester.id eq id2 }.single()[rawTester.value] shouldBeEqualTo -1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `null transform`(testDb: TestDB) {
        val tester = object: IntIdTable("tester") {
            val value: Column<TransformDataHolder?> = integer("value")
                .nullTransform(DataHolderNullTransformer())
        }
        val rawTester = object: IntIdTable("tester") {
            val value: Column<Int> = integer("value")
        }

        withTables(testDb, tester) {
            val result = tester.insert {
                it[value] = null
            }
            result[tester.value].shouldBeNull()
            tester.selectAll().single()[tester.value].shouldBeNull()
            rawTester.selectAll().single()[rawTester.value] shouldBeEqualTo 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `transform with default`(testDb: TestDB) {
        val tester = object: IntIdTable("tester") {
            val value: Column<TransformDataHolder> = integer("value")
                .transform(DataHolderTransformer())
                .default(TransformDataHolder(1))
        }

        withTables(testDb, tester) {
            val entry = tester.insert { }
            entry[tester.value].value shouldBeEqualTo 1
            tester.selectAll().first()[tester.value].value shouldBeEqualTo 1
        }
    }

    /**
     * Batch Insert
     *
     * ```sql
     * INSERT INTO "TEST-BATCH-INSERT" (V1) VALUES (1)
     * INSERT INTO "TEST-BATCH-INSERT" (V1) VALUES (2)
     * INSERT INTO "TEST-BATCH-INSERT" (V1) VALUES (3)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `transform in batch insert`(testDb: TestDB) {
        val tester = object: IntIdTable("test-batch-insert") {
            val v1 = integer("v1")
                .transform(wrap = { TransformDataHolder(it) }, unwrap = { it.value })
        }

        withTables(testDb, tester) {
            tester.batchInsert(listOf(1, 2, 3)) {
                this[tester.v1] = TransformDataHolder(it)
            }

            tester.selectAll()
                .orderBy(tester.v1)
                .map { it[tester.v1].value } shouldBeEqualTo listOf(1, 2, 3)
        }
    }

    /**
     * INSERT
     *
     * ```sql
     * INSERT INTO "TEST-UPDATE" (V1) VALUES (1)
     * ```
     *
     * UPDATE
     * ```sql
     * UPDATE "TEST-UPDATE" SET V1=2 WHERE "TEST-UPDATE".ID = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `transform in update`(testDb: TestDB) {
        val tester = object: IntIdTable("test-update") {
            val v1 = integer("v1")
                .transform(wrap = { TransformDataHolder(it) }, unwrap = { it.value })
        }

        withTables(testDb, tester) {
            val id = tester.insertAndGetId {
                it[v1] = TransformDataHolder(1)
            }

            tester.update(where = { tester.id eq id }) {
                it[tester.v1] = TransformDataHolder(2)
            }

            tester.selectAll().first()[tester.v1].value shouldBeEqualTo 2
        }
    }
}
