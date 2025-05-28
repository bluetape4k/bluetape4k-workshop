package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.greater
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.plus
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.times
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.mergeFrom
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * `MERGE INTO` 문을 테스트합니다.
 *
 * SQL MERGE INTO 구문은 데이터베이스에서 조건에 따라 데이터를 삽입, 갱신, 삭제하는 작업을 한 번에 수행할 수 있게 해주는 강력한 기능입니다
 *
 * 참고: [MERGE INTO 에 대한 설명](https://www.perplexity.ai/search/sql-merge-into-gumune-daehae-s-y_xKDfwFR8ewN6qIY9jqJw)
 */
class MergeTableTest: MergeBaseTest() {

    companion object: KLogging()

    private fun SqlExpressionBuilder.defaultOnCondition(): Op<Boolean> =
        Source.key eq Dest.key

    /**
     * [mergeFrom] 함수를 사용하여 [org.jetbrains.exposed.sql.statements.MergeStatement.whenNotMatchedInsert] 를 테스트합니다.
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     *  WHEN NOT MATCHED THEN
     *      INSERT ("at", "key", "value", optional_value)
     *      VALUES (
     *          '2000-01-01T00:00:00',
     *          "source"."key",
     *          ("source"."value" * 2),
     *          CONCAT('optional::', "source"."key")
     *      )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                source,
                on = { defaultOnCondition() }
            ) {
                whenNotMatchedInsert {
                    it[dest.key] = source.key
                    it[dest.value] = source.value * 2
                    it[dest.optional] = stringLiteral("optional::") + source.key
                }
            }

            val destRow = dest.getByKey("only-in-source-1")
            destRow[dest.value] shouldBeEqualTo 2
            destRow[dest.optional] shouldBeEqualTo "optional::only-in-source-1"
            destRow[dest.at] shouldBeEqualTo TEST_DEFAULT_DATE_TIME
        }
    }


    /**
     * [mergeFrom] 함수를 사용하여 [org.jetbrains.exposed.sql.statements.MergeStatement.whenMatchedThenUpdate] 를 테스트합니다.
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest dest_alias
     * USING "source" source_alias ON source_alias."key" = dest_alias."key"
     *  WHEN NOT MATCHED THEN
     *      INSERT ("at", "key", "value")
     *      VALUES (
     *          '2000-01-01T00:00:00',
     *          source_alias."key",
     *          (source_alias."value" * 2)
     *      )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert by alias`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            val destAlias = dest.alias("dest_alias")
            val sourceAlias = source.alias("source_alias")

            destAlias.mergeFrom(
                sourceAlias,
                on = { sourceAlias[source.key] eq destAlias[dest.key] }
            ) {
                whenNotMatchedInsert {
                    it[dest.key] = sourceAlias[source.key]
                    it[dest.value] = sourceAlias[source.value] * 2
                }
            }

            val destRow = dest.getByKey("only-in-source-1")
            destRow[dest.value] shouldBeEqualTo 2
        }
    }

    /**
     * [mergeFrom] 함수를 사용하여 [org.jetbrains.exposed.sql.statements.MergeStatement.whenMatchedUpdate] 를 테스트합니다.
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     *  WHEN MATCHED THEN
     *      UPDATE SET "value"=(("source"."value" + dest."value") * 2),
     *                 optional_value=CONCAT(CONCAT("source"."key", '::'), dest."key")
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedUpdate`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                source,
                on = { defaultOnCondition() }
            ) {
                whenMatchedUpdate {
                    it[dest.value] = (source.value + dest.value) * 2
                    it[dest.optional] = source.key + stringLiteral("::") + dest.key
                }
            }

            val destRow = dest.getByKey("in-source-and-dest-1")
            destRow[dest.value] shouldBeEqualTo 22
            destRow[dest.optional] shouldBeEqualTo "in-source-and-dest-1::in-source-and-dest-1"
            destRow[dest.at] shouldBeEqualTo TEST_DEFAULT_DATE_TIME
        }
    }


    /**
     * MergeFrom with whenMatchedUpdate
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest dest_alias
     * USING "source" ON "source"."key" = dest_alias."key"
     *  WHEN MATCHED THEN
     *      UPDATE SET "value"=(("source"."value" + dest_alias."value") * 2),
     *                 optional_value=CONCAT(CONCAT("source"."key", '::'), dest_alias."key")
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedUpdate by alias`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            val destAlias = dest.alias("dest_alias")

            destAlias.mergeFrom(
                source,
                on = { source.key eq destAlias[dest.key] }
            ) {
                whenMatchedUpdate {
                    it[dest.value] = (source.value + destAlias[dest.value]) * 2
                    it[dest.optional] = source.key + stringLiteral("::") + destAlias[dest.key]
                }
            }

            val destRow = dest.getByKey("in-source-and-dest-1")
            destRow[dest.value] shouldBeEqualTo 22
            destRow[dest.optional] shouldBeEqualTo "in-source-and-dest-1::in-source-and-dest-1"
            destRow[dest.at] shouldBeEqualTo TEST_DEFAULT_DATE_TIME
        }
    }

    /**
     * MergeFrom with whenMatchedDelete
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     * WHEN MATCHED THEN
     *      DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedDelete`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                source,
                on = { defaultOnCondition() }
            ) {
                whenMatchedDelete()
            }

            dest.getByKeyOrNull("in-source-and-dest-1").shouldBeNull()
            dest.getByKeyOrNull("in-source-and-dest-2").shouldBeNull()
            dest.getByKeyOrNull("in-source-and-dest-3").shouldBeNull()
            dest.getByKeyOrNull("in-source-and-dest-4").shouldBeNull()
        }
    }

    /**
     * MergeFrom with whenNotMatchedInsert and whenMatchedUpdate
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     *  WHEN NOT MATCHED AND ("source"."value" > 2) THEN
     *      INSERT ("at", "key", "value")
     *      VALUES ('2000-01-01T00:00:00', "source"."key", "source"."value")
     *  WHEN MATCHED AND (dest."value" > 20) THEN
     *      UPDATE SET "value"=("source"."value" + dest."value")
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert and whenMatchedUpdate`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                source,
                on = { defaultOnCondition() }
            ) {
                whenNotMatchedInsert(and = (source.value greater 2)) {
                    it[dest.key] = source.key
                    it[dest.value] = source.value
                }

                whenMatchedUpdate(and = (dest.value greater 20)) {
                    it[dest.value] = source.value + dest.value
                }
            }

            dest.getByKeyOrNull("only-in-source-1").shouldBeNull()
            dest.getByKeyOrNull("only-in-source-2").shouldBeNull()
            dest.getByKeyOrNull("only-in-source-3").shouldNotBeNull()
            dest.getByKeyOrNull("only-in-source-4").shouldNotBeNull()

            dest.getByKey("in-source-and-dest-1")[dest.value] shouldBeEqualTo 10
            dest.getByKey("in-source-and-dest-2")[dest.value] shouldBeEqualTo 20
            dest.getByKey("in-source-and-dest-3")[dest.value] shouldBeEqualTo 33
            dest.getByKey("in-source-and-dest-4")[dest.value] shouldBeEqualTo 44
        }
    }

    /**
     * MergeFrom with whenMatchedDelete and condition
     *
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     *  WHEN MATCHED AND (("source"."value" > 2) AND (dest."value" > 20)) THEN
     *      DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedDelete and condition`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                source,
                on = { defaultOnCondition() }
            ) {
                whenMatchedDelete(and = (source.value greater 2) and (dest.value greater 20))
            }

            dest.getByKeyOrNull("in-source-and-dest-1").shouldNotBeNull()
            dest.getByKeyOrNull("in-source-and-dest-2").shouldNotBeNull()
            dest.getByKeyOrNull("in-source-and-dest-3").shouldBeNull()
            dest.getByKeyOrNull("in-source-and-dest-4").shouldBeNull()
        }
    }

    /**
     * MergeFrom with multiple clauses
     *
     * Postgres
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     *  WHEN NOT MATCHED AND ("source"."value" = 1) THEN
     *      INSERT ("at", "key", "value", optional_value)
     *      VALUES ('2000-01-01T00:00:00', "source"."key", "source"."value", 'one')
     *  WHEN NOT MATCHED AND ("source"."value" = 2) THEN
     *      INSERT ("at", "key", "value", optional_value)
     *      VALUES ('2000-01-01T00:00:00', "source"."key", "source"."value", 'two')
     *  WHEN NOT MATCHED THEN
     *      INSERT ("at", "key", "value", optional_value)
     *      VALUES ('2000-01-01T00:00:00', "source"."key", "source"."value", 'three-and-more')
     *  WHEN MATCHED AND ("source"."value" = 1) THEN
     *      DELETE
     *  WHEN MATCHED AND ("source"."value" = 1) THEN
     *      UPDATE SET "key"="source"."key",
     *                 "value"=((dest."value" + "source"."value") * 10)
     *  WHEN MATCHED AND ("source"."value" = 2) THEN
     *      UPDATE SET "key"="source"."key",
     *                 "value"=((dest."value" + "source"."value") * 100)
     *  WHEN MATCHED AND ("source"."value" = 3) THEN
     *      DELETE
     *  WHEN MATCHED THEN
     *      UPDATE SET "key"="source"."key",
     *                 "value"=1000
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with multiple clauses`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(source, on = { defaultOnCondition() }) {
                whenNotMatchedInsert(and = (source.value eq 1)) {
                    it[dest.key] = source.key
                    it[dest.value] = source.value
                    it[dest.optional] = "one"
                }
                whenNotMatchedInsert(and = (source.value eq 2)) {
                    it[dest.key] = source.key
                    it[dest.value] = source.value
                    it[dest.optional] = "two"
                }
                whenNotMatchedInsert {
                    it[dest.key] = source.key
                    it[dest.value] = source.value
                    it[dest.optional] = "three-and-more"
                }

                whenMatchedDelete(and = (source.value eq 1))
                whenMatchedUpdate(and = (source.value eq 1)) {
                    it[dest.key] = source.key
                    it[dest.value] = (dest.value + source.value) * 10
                }
                whenMatchedUpdate(and = (source.value eq 2)) {
                    it[dest.key] = source.key
                    it[dest.value] = (dest.value + source.value) * 100
                }
                whenMatchedDelete(and = (source.value eq 3))

                whenMatchedUpdate {
                    it[dest.key] = source.key
                    it[dest.value] = 1000
                }
            }

            dest.getByKey("only-in-source-1")[dest.optional] shouldBeEqualTo "one"
            dest.getByKey("only-in-source-2")[dest.optional] shouldBeEqualTo "two"
            dest.getByKey("only-in-source-3")[dest.optional] shouldBeEqualTo "three-and-more"
            dest.getByKey("only-in-source-4")[dest.optional] shouldBeEqualTo "three-and-more"


            dest.getByKeyOrNull("in-source-and-dest-1").shouldBeNull()
            dest.getByKey("in-source-and-dest-2")[dest.value] shouldBeEqualTo 2200
            dest.getByKeyOrNull("in-source-and-dest-3").shouldBeNull()
            dest.getByKey("in-source-and-dest-4")[dest.value] shouldBeEqualTo 1000
        }
    }

    /**
     * MergeFrom with auto generated on condition
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING "source" ON dest.id="source".id
     *  WHEN NOT MATCHED THEN
     *      INSERT (id, "value") VALUES ("source".id, "source".test_value)
     * ```
     *
     * With alias:
     * ```sql
     * MERGE INTO dest dest_alias
     * USING "source" source_alias ON dest_alias.id=source_alias.id
     *  WHEN NOT MATCHED THEN
     *      INSERT (id, "value") VALUES (source_alias.id, source_alias.test_value)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `auto generated on condition`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_MYSQL_MARIADB }

        val source = object: IdTable<Int>("source") {
            override val id = integer("id").entityId()
            val value = varchar("test_value", 128)
            override val primaryKey = PrimaryKey(id)
        }
        val dest = object: IdTable<Int>("dest") {
            override val id = integer("id").entityId()
            val value = varchar("value", 128)
            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, source, dest) {
            source.insert {
                it[id] = 1
                it[value] = "1"
            }
            source.insert {
                it[id] = 2
                it[value] = "2"
            }

            dest.mergeFrom(source) {
                whenNotMatchedInsert {
                    it[dest.id] = source.id
                    it[dest.value] = source.value
                }
            }

            val destAlias = dest.alias("dest_alias")
            val sourceAlias = source.alias("source_alias")

            destAlias.mergeFrom(sourceAlias) {
                whenNotMatchedInsert {
                    it[dest.id] = sourceAlias[source.id]
                    it[dest.value] = sourceAlias[source.value]
                }
            }
        }
    }

    /**
     * MergeFrom with whenNotMatchedDoNothing
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     *  WHEN NOT MATCHED AND ("source"."value" > 1) THEN
     *      DO NOTHING
     *  WHEN NOT MATCHED THEN
     *      INSERT ("at", "key", "value")
     *      VALUES ('2000-01-01T00:00:00', "source"."key", ("source"."value" + 100))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatched do nothing in postgres`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES)

        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                source,
                on = { defaultOnCondition() }
            ) {
                whenNotMatchedDoNothing(and = source.value greater 1)
                whenNotMatchedInsert {
                    it[dest.key] = source.key
                    it[dest.value] = source.value + 100
                }
            }

            dest.selectAll()
                .where { dest.key eq "only-in-source-1" }
                .first()[dest.value] shouldBeEqualTo 101

            dest.selectAll()
                .where { dest.key inList listOf("only-in-source-2", "only-in-source-3") }
                .firstOrNull().shouldBeNull()
        }
    }

    /**
     * Postgres에서만 `whenMatchedDoNothing` 을 지원합니다.
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING "source" ON "source"."key" = dest."key"
     *  WHEN MATCHED AND ("source"."value" = 1) THEN
     *      DO NOTHING
     *  WHEN MATCHED THEN
     *      DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatched do nothing in postgres`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES)

        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                source,
                on = { defaultOnCondition() }
            ) {
                whenMatchedDoNothing(and = source.value eq 1)
                whenMatchedDelete()
            }

            dest.selectAll()
                .where { dest.key eq "in-source-and-dest-1" }
                .first()[dest.value] shouldBeEqualTo 10

            dest.selectAll()
                .where { dest.key inList listOf("in-source-and-dest-2", "in-source-and-dest-3") }
                .toList()
                .shouldBeEmpty()
        }
    }

    /**
     * Postgres 에서 `OVERRIDING SYSTEM VALUE` 옵션을 사용하여 DEST 테이블의 ID 값을 덮어쓰는 테스트입니다.
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING src ON dest.id=src.id
     *  WHEN NOT MATCHED THEN
     *      INSERT (id) OVERRIDING SYSTEM VALUE VALUES (1)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `with overridingSystemValue option in postgres`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES)

        val source = object: IntIdTable("src") {}
        val dest = object: IdTable<Int>("dest") {
            override val id = integer("id")
                .withDefinition("generated always as identity")
                .entityId()
            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, source, dest) {
            val id = source.insertAndGetId { }

            dest.mergeFrom(source) {
                // `overridingSystemValue` allows to overwrite `generated always as identity` value
                // otherwise Postgres will throw exception that auto generated value must not be overwritten
                whenNotMatchedInsert(overridingSystemValue = true) {
                    it[dest.id] = id.value
                }
            }

            dest.selectAll().single()[dest.id].value shouldBeEqualTo id.value
        }
    }

    /**
     * Postgres 에서 `OVERRIDING USER VALUE` 옵션을 사용하여 DEST 테이블의 ID 값을 덮어쓰는 테스트입니다.
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING src ON dest.id=src.id
     *  WHEN NOT MATCHED THEN
     *      INSERT (id) OVERRIDING USER VALUE VALUES (src.id)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `with overridingUserValue option in postgres`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES)

        val source = object: IntIdTable("src") {}

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS dest (
         *      id INT GENERATED BY DEFAULT AS IDENTITY(SEQUENCE NAME test_seq START WITH 100)PRIMARY KEY
         * )
         * ```
         */
        val sequenceStartNumber = 100
        val dest = object: IdTable<Int>("dest") {
            override val id = integer("id")
                .withDefinition("GENERATED BY DEFAULT AS IDENTITY(SEQUENCE NAME test_seq START WITH $sequenceStartNumber)")
                .entityId()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, source, dest) {
            try {
                source.insertAndGetId { }

                dest.mergeFrom(source) {
                    // `overridingUserValue` here allows to avoid setting id value from source table, and take generated by sequence instead
                    whenNotMatchedInsert(overridingUserValue = true) {
                        it[dest.id] = source.id
                    }
                }

                dest.selectAll().single()[dest.id].value shouldBeEqualTo sequenceStartNumber
            } finally {
                SchemaUtils.drop(dest)
                exec("drop sequence if exists testOverridingUserValueSequence")
            }
        }
    }

    /**
     * MergeFrom with default value
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING source ON dest.id=source.id
     *  WHEN NOT MATCHED THEN
     *      INSERT DEFAULT VALUES
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert default value`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES)

        val source = object: IntIdTable("source") {
            val value = varchar("value", 128)
        }
        val dest = object: IntIdTable("dest") {
            // `withDefinition()` here is a workaround to avoid insert explicitly pass default value
            val value = varchar("value", 128)
                .withDefinition("DEFAULT", stringLiteral("default-test-value"))
                .databaseGenerated()
        }

        withTables(testDB, source, dest) {
            source.insert {
                it[value] = "user-defined-value"
            }

            dest.mergeFrom(source) {
                whenNotMatchedInsert {}  // WHEN NOT MATCHED THEN INSERT DEFAULT VALUES
            }

            dest.selectAll().single()[dest.value] shouldBeEqualTo "default-test-value"
        }
    }

    /**
     * MergeFrom with const condition
     *
     * Postgres:
     * ```sql
     * MERGE INTO dest
     * USING (
     *      SELECT "source".id,
     *             "source"."key",
     *             "source"."value",
     *             "source".optional_value,
     *             "source"."at"
     *        FROM "source"
     *       WHERE "source"."key" = 'only-in-source-1'
     *      ) as src ON dest."key" = src."key"
     * WHEN NOT MATCHED THEN
     *      INSERT ("at", "key", "value")
     *      VALUES ('2000-01-01T00:00:00', src."key", src."value")
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with const condition`(testDB: TestDB) {
        val filteredSourceQuery = Source.selectAll()
            .where { Source.key eq "only-in-source-1" }
            .alias("src")

        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                filteredSourceQuery,
                on = { Dest.key eq filteredSourceQuery[Source.key] },
            ) {
                whenNotMatchedInsert {
                    it[dest.key] = filteredSourceQuery[source.key]
                    it[dest.value] = filteredSourceQuery[source.value]
                }
            }

            dest.getByKey("only-in-source-1")[dest.value] shouldBeEqualTo 1
            dest.selectAll()
                .where { Dest.key eq "only-in-source-2" }
                .firstOrNull().shouldBeNull()
        }
    }

    /**
     * Postgres 기능 중에 다른 DB에서 지원되지 않는 기능을 확인합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `postgres features are unsupported in other databases`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB !in TestDB.ALL_POSTGRES)

        withMergeTestTablesAndDefaultData(testDB) { dest, source ->

            // DO NOTHING
            expectException<UnsupportedByDialectException> {
                dest.mergeFrom(source, on = { defaultOnCondition() }) {
                    whenNotMatchedDoNothing(and = source.value greater 1)
                }
            }

            // OVERRIDING SYSTEM VALUE
            expectException<UnsupportedByDialectException> {
                dest.mergeFrom(source) {
                    whenNotMatchedInsert(overridingSystemValue = true) {}
                }
            }

            // OVERRIDING USER VALUE
            expectException<UnsupportedByDialectException> {
                dest.mergeFrom(source) {
                    whenNotMatchedInsert(overridingUserValue = true) {}
                }
            }
        }
    }
}
