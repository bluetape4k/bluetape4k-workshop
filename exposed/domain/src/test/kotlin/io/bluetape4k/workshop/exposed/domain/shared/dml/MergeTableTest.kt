package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.mergeFrom
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * 참고: [MERGE INTO 에 대한 설명](https://www.perplexity.ai/search/sql-merge-into-gumune-daehae-s-y_xKDfwFR8ewN6qIY9jqJw)
 */
class MergeTableTest: MergeBaseTest() {

    companion object: KLogging()

    private fun SqlExpressionBuilder.defaultOnCondition(): Op<Boolean> =
        Source.key eq Dest.key

    /**
     * [mergeFrom] 함수를 사용하여 [org.jetbrains.exposed.sql.statements.MergeStatement.whenNotMatchedInsert] 를 테스트합니다.
     *
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING MERGE_TEST_SOURCE ON MERGE_TEST_SOURCE.MERGE_TEST_KEY = MERGE_TEST_DEST.MERGE_TEST_KEY
     * WHEN NOT MATCHED THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', MERGE_TEST_SOURCE.MERGE_TEST_KEY, (MERGE_TEST_SOURCE.MERGE_TEST_VALUE * 2), CONCAT('optional::', MERGE_TEST_SOURCE.MERGE_TEST_KEY))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * ```sql
     * MERGE INTO MERGE_TEST_DEST dest_alias
     * USING MERGE_TEST_SOURCE source_alias
     *    ON source_alias.MERGE_TEST_KEY = dest_alias.MERGE_TEST_KEY
     * WHEN NOT MATCHED THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE)
     *      VALUES ('2000-01-01T00:00:00', source_alias.MERGE_TEST_KEY, (source_alias.MERGE_TEST_VALUE * 2))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert by alias`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING MERGE_TEST_SOURCE
     *    ON MERGE_TEST_SOURCE.MERGE_TEST_KEY = MERGE_TEST_DEST.MERGE_TEST_KEY
     *  WHEN MATCHED THEN
     *          UPDATE SET MERGE_TEST_VALUE=((MERGE_TEST_SOURCE.MERGE_TEST_VALUE + MERGE_TEST_DEST.MERGE_TEST_VALUE) * 2),
     *                     MERGE_TEST_OPTIONAL_VALUE=CONCAT(CONCAT(MERGE_TEST_SOURCE.MERGE_TEST_KEY, '::'), MERGE_TEST_DEST.MERGE_TEST_KEY)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedUpdate`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * ```sql
     * MERGE INTO MERGE_TEST_DEST dest_alias
     * USING MERGE_TEST_SOURCE
     *    ON MERGE_TEST_SOURCE.MERGE_TEST_KEY = dest_alias.MERGE_TEST_KEY
     * WHEN MATCHED THEN
     *      UPDATE SET
     *          MERGE_TEST_VALUE=((MERGE_TEST_SOURCE.MERGE_TEST_VALUE + dest_alias.MERGE_TEST_VALUE) * 2),
     *          MERGE_TEST_OPTIONAL_VALUE=CONCAT(CONCAT(MERGE_TEST_SOURCE.MERGE_TEST_KEY, '::'), dest_alias.MERGE_TEST_KEY)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedUpdate by alias`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING MERGE_TEST_SOURCE
     *    ON MERGE_TEST_DEST.MERGE_TEST_KEY = MERGE_TEST_SOURCE.MERGE_TEST_KEY
     * WHEN MATCHED THEN DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedDelete`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING MERGE_TEST_SOURCE
     *    ON MERGE_TEST_DEST.MERGE_TEST_KEY = MERGE_TEST_SOURCE.MERGE_TEST_KEY
     * WHEN NOT MATCHED AND (MERGE_TEST_SOURCE.MERGE_TEST_VALUE > 2) THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, sub.MERGE_TEST_VALUE)
     * WHEN MATCHED AND (MERGE_TEST_DEST.MERGE_TEST_VALUE > 20) THEN
     *      UPDATE SET MERGE_TEST_VALUE=(MERGE_TEST_SOURCE.MERGE_TEST_VALUE + MERGE_TEST_DEST.MERGE_TEST_VALUE)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert and whenMatchedUpdate`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * MERGE INTO MERGE_TEST_DEST
     * USING MERGE_TEST_SOURCE
     *    ON MERGE_TEST_DEST.MERGE_TEST_KEY = MERGE_TEST_SOURCE.MERGE_TEST_KEY
     *  WHEN MATCHED AND ((MERGE_TEST_SOURCE.MERGE_TEST_VALUE > 2) AND (MERGE_TEST_DEST.MERGE_TEST_VALUE > 20))
     *  THEN DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedDelete and condition`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING MERGE_TEST_SOURCE ON MERGE_TEST_DEST.MERGE_TEST_KEY = MERGE_TEST_SOURCE.MERGE_TEST_KEY
     * WHEN NOT MATCHED AND (MERGE_TEST_SOURCE.MERGE_TEST_VALUE = 1) THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', MERGE_TEST_SOURCE.MERGE_TEST_KEY, MERGE_TEST_SOURCE.MERGE_TEST_VALUE, 'one')
     * WHEN NOT MATCHED AND (MERGE_TEST_SOURCE.MERGE_TEST_VALUE = 2) THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', MERGE_TEST_SOURCE.MERGE_TEST_KEY, MERGE_TEST_SOURCE.MERGE_TEST_VALUE, 'two')
     * WHEN NOT MATCHED THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', MERGE_TEST_SOURCE.MERGE_TEST_KEY, MERGE_TEST_SOURCE.MERGE_TEST_VALUE, 'three-and-more')
     * WHEN MATCHED AND (MERGE_TEST_SOURCE.MERGE_TEST_VALUE = 1) THEN
     *      DELETE
     * WHEN MATCHED AND (MERGE_TEST_SOURCE.MERGE_TEST_VALUE = 1) THEN
     *      UPDATE SET MERGE_TEST_KEY=sub.MERGE_TEST_KEY,
     *                 MERGE_TEST_VALUE=((MERGE_TEST_DEST.MERGE_TEST_VALUE + MERGE_TEST_SOURCE.MERGE_TEST_VALUE) * 10)
     * WHEN MATCHED AND (MERGE_TEST_SOURCE.MERGE_TEST_VALUE = 2) THEN
     *      UPDATE SET MERGE_TEST_KEY=sub.MERGE_TEST_KEY,
     *                 MERGE_TEST_VALUE=((MERGE_TEST_DEST.MERGE_TEST_VALUE + MERGE_TEST_SOURCE.MERGE_TEST_VALUE) * 100)
     * WHEN MATCHED AND (MERGE_TEST_SOURCE.MERGE_TEST_VALUE = 3) THEN
     *      DELETE
     * WHEN MATCHED THEN
     *      UPDATE SET MERGE_TEST_KEY=MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *                 MERGE_TEST_VALUE=1000
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with multiple clauses`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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
     * H2:
     * ```sql
     * MERGE INTO TEST_DEST
     * USING TEST_SOURCE ON TEST_DEST.ID=TEST_SOURCE.ID
     *  WHEN NOT MATCHED THEN
     *      INSERT (ID, TEST_VALUE) VALUES (TEST_SOURCE.ID, TEST_SOURCE.TEST_VALUE)
     * ```
     *
     * With alias:
     * ```sql
     * MERGE INTO TEST_DEST dest_alias
     * USING TEST_SOURCE source_alias ON dest_alias.ID=source_alias.ID
     *  WHEN NOT MATCHED THEN
     *      INSERT (ID, TEST_VALUE) VALUES (source_alias.ID, source_alias.TEST_VALUE)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `auto generated on condition`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb !in TestDB.ALL_MYSQL }

        val source = object: IdTable<Int>("test_source") {
            override val id = integer("id").entityId()
            val value = varchar("test_value", 128)
            override val primaryKey = PrimaryKey(id)
        }
        val dest = object: IdTable<Int>("test_dest") {
            override val id = integer("id").entityId()
            val value = varchar("test_value", 128)
            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDb, source, dest) {
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
     * ```sql
     * MERGE INTO merge_test_dest
     * USING merge_test_source ON merge_test_dest.merge_test_key = merge_test_source.merge_test_key
     * WHEN NOT MATCHED AND (merge_test_source.merge_test_value > 1) THEN DO NOTHING
     * WHEN NOT MATCHED THEN
     *      INSERT (merge_test_at, merge_test_key, merge_test_value)
     *      VALUES ('2000-01-01T00:00:00', merge_test_source.merge_test_key, (merge_test_source.merge_test_value + 100))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatched do nothing in postgres`(testDb: TestDB) {
        Assumptions.assumeTrue(testDb in TestDB.ALL_POSTGRES)

        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatched do nothing in postgres`(testDb: TestDB) {
        Assumptions.assumeTrue(testDb in TestDB.ALL_POSTGRES)

        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `with overridingSystemValue option in postgres`(testDb: TestDB) {
        Assumptions.assumeTrue(testDb in TestDB.ALL_POSTGRES)

        val source = object: IntIdTable() {}
        val dest = object: IdTable<Int>() {
            override val id = integer("id")
                .withDefinition("generated always as identity")
                .entityId()
            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDb, source, dest) {
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
     * MergeFrom with overridingUserValue option
     *
     * ```sql
     * MERGE INTO "with overridinguservalue option in postgres$dest$1"
     * USING "with overridinguservalue option in postgres$source$1" ON "with overridinguservalue option in postgres$dest$1".id="with overridinguservalue option in postgres$source$1".id
     *  WHEN NOT MATCHED THEN
     *          INSERT (id) OVERRIDING USER VALUE
     *          VALUES ("with overridinguservalue option in postgres$source$1".id)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `with overridingUserValue option in postgres`(testDb: TestDB) {
        Assumptions.assumeTrue(testDb in TestDB.ALL_POSTGRES)

        val source = object: IntIdTable() {}

        val sequenceStartNumber = 100
        val dest = object: IdTable<Int>() {
            override val id = integer("id")
                .withDefinition("GENERATED BY DEFAULT AS IDENTITY(SEQUENCE NAME testOverridingUserValueSequence START WITH $sequenceStartNumber)")
                .entityId()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDb, source, dest) {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert default value`(testDb: TestDB) {
        Assumptions.assumeTrue(testDb in TestDB.ALL_POSTGRES)

        val source = object: IntIdTable("test_source") {
            val value = varchar("value", 128)
        }
        val dest = object: IntIdTable("test_dest") {
            // `withDefinition()` here is a workaround to avoid insert explicitly pass default value
            val value = varchar("value", 128)
                .withDefinition("DEFAULT", stringLiteral("default-test-value"))
                .databaseGenerated()
        }

        withTables(testDb, source, dest) {
            source.insert {
                it[value] = "user-defined-value"
            }

            dest.mergeFrom(source) {
                whenNotMatchedInsert {}
            }

            dest.selectAll().single()[dest.value] shouldBeEqualTo "default-test-value"
        }
    }

    /**
     * MergeFrom with const condition
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     *       WHERE MERGE_TEST_SOURCE.MERGE_TEST_KEY = 'only-in-source-1'
     * ) as sub ON MERGE_TEST_DEST.MERGE_TEST_KEY = sub.MERGE_TEST_KEY
     * WHEN NOT MATCHED THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, sub.MERGE_TEST_VALUE)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with const condition`(testDb: TestDB) {
        val filteredSourceQuery = Source.selectAll()
            .where { Source.key eq "only-in-source-1" }
            .alias("sub")

        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `postgres features are unsupported in other databases`(testDb: TestDB) {
        Assumptions.assumeTrue(testDb !in TestDB.ALL_POSTGRES)

        withMergeTestTablesAndDefaultData(testDb) { dest, source ->

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
