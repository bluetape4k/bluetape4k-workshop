package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.TestDB
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryAlias
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.mergeFrom
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MergeSelectTest: MergeBaseTest() {

    companion object: KLogging()

    private val sourceQuery: QueryAlias = Source.selectAll().alias("sub")

    private fun SqlExpressionBuilder.defaultOnCondition(): Op<Boolean> =
        Dest.key eq sourceQuery[Source.key]

    /**
     * Mrget into from a select query
     *
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON MERGE_TEST_DEST.MERGE_TEST_KEY = sub.MERGE_TEST_KEY
     * WHEN NOT MATCHED
     * THEN INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, (sub.MERGE_TEST_VALUE * 2), CONCAT('optional::', sub.MERGE_TEST_KEY))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert`(testDB: TestDB) {
        withMergeTestTablesAndDefaultData(testDB) { dest, source ->
            dest.mergeFrom(
                sourceQuery,
                on = { defaultOnCondition() }
            ) {
                whenNotMatchedInsert {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = sourceQuery[source.value] * 2
                    it[dest.optional] = stringLiteral("optional::") + sourceQuery[source.key]
                }
            }

            val destRow = dest.getByKey("only-in-source-1")
            destRow[dest.value] shouldBeEqualTo 2
            destRow[dest.optional] shouldBeEqualTo "optional::only-in-source-1"
            destRow[dest.at] shouldBeEqualTo TEST_DEFAULT_DATE_TIME
        }
    }

    /**
     * Merge into from a select query with alias
     * ```sql
     * MERGE INTO MERGE_TEST_DEST dest_alias
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON sub.MERGE_TEST_KEY = dest_alias.MERGE_TEST_KEY
     * WHEN NOT MATCHED THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, (sub.MERGE_TEST_VALUE * 2))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert by alias`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            val destAlias = dest.alias("dest_alias")

            destAlias.mergeFrom(
                sourceQuery,
                on = { sourceQuery[source.key] eq destAlias[dest.key] }
            ) {
                whenNotMatchedInsert {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = sourceQuery[source.value] * 2
                }
            }

            val destRow = dest.getByKey("only-in-source-1")
            destRow[dest.value] shouldBeEqualTo 2
        }
    }

    /**
     * MergeFrom with whenMatchedUpdate
     *
     * ```sql
     * MERGE INTO MERGE_TEST_DEST
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON MERGE_TEST_DEST.MERGE_TEST_KEY = sub.MERGE_TEST_KEY
     * WHEN MATCHED THEN
     *      UPDATE SET
     *          MERGE_TEST_VALUE=((sub.MERGE_TEST_VALUE + MERGE_TEST_DEST.MERGE_TEST_VALUE) * 2),
     *          MERGE_TEST_OPTIONAL_VALUE=CONCAT(CONCAT(sub.MERGE_TEST_KEY, '::'), MERGE_TEST_DEST.MERGE_TEST_KEY)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedUpdate`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            dest.mergeFrom(
                sourceQuery,
                on = { defaultOnCondition() }
            ) {
                whenMatchedUpdate {
                    it[dest.value] = (sourceQuery[source.value] + dest.value) * 2
                    it[dest.optional] = sourceQuery[source.key] + stringLiteral("::") + dest.key
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
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON sub.MERGE_TEST_KEY = dest_alias.MERGE_TEST_KEY
     * WHEN MATCHED THEN
     *      UPDATE SET
     *          MERGE_TEST_VALUE=((sub.MERGE_TEST_VALUE + dest_alias.MERGE_TEST_VALUE) * 2),
     *          MERGE_TEST_OPTIONAL_VALUE=CONCAT(CONCAT(sub.MERGE_TEST_KEY, '::'), dest_alias.MERGE_TEST_KEY)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedUpdate by alias`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            val destAlias = dest.alias("dest_alias")

            destAlias.mergeFrom(
                sourceQuery,
                on = { sourceQuery[source.key] eq destAlias[dest.key] }
            ) {
                whenMatchedUpdate {
                    it[dest.value] = (sourceQuery[source.value] + destAlias[dest.value]) * 2
                    it[dest.optional] = sourceQuery[source.key] + stringLiteral("::") + destAlias[dest.key]
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
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON MERGE_TEST_DEST.MERGE_TEST_KEY = sub.MERGE_TEST_KEY
     * WHEN MATCHED THEN DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedDelete`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            dest.mergeFrom(
                sourceQuery,
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
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON MERGE_TEST_DEST.MERGE_TEST_KEY = sub.MERGE_TEST_KEY
     * WHEN NOT MATCHED AND (sub.MERGE_TEST_VALUE > 2) THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, sub.MERGE_TEST_VALUE)
     * WHEN MATCHED AND (MERGE_TEST_DEST.MERGE_TEST_VALUE > 20) THEN
     *      UPDATE SET MERGE_TEST_VALUE=(sub.MERGE_TEST_VALUE + MERGE_TEST_DEST.MERGE_TEST_VALUE)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenNotMatchedInsert and whenMatchedUpdate`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            dest.mergeFrom(
                sourceQuery,
                on = { defaultOnCondition() }
            ) {
                whenNotMatchedInsert(and = (sourceQuery[source.value] greater 2)) {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = sourceQuery[source.value]
                }

                whenMatchedUpdate(and = (dest.value greater 20)) {
                    it[dest.value] = sourceQuery[source.value] + dest.value
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
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON MERGE_TEST_DEST.MERGE_TEST_KEY = sub.MERGE_TEST_KEY
     * WHEN MATCHED AND ((sub.MERGE_TEST_VALUE > 2) AND (MERGE_TEST_DEST.MERGE_TEST_VALUE > 20)) THEN DELETE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with whenMatchedDelete and condition`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            dest.mergeFrom(
                sourceQuery,
                on = { defaultOnCondition() }
            ) {
                whenMatchedDelete(and = (sourceQuery[source.value] greater 2) and (dest.value greater 20))
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
     * USING (
     *      SELECT MERGE_TEST_SOURCE.ID,
     *             MERGE_TEST_SOURCE.MERGE_TEST_KEY,
     *             MERGE_TEST_SOURCE.MERGE_TEST_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_OPTIONAL_VALUE,
     *             MERGE_TEST_SOURCE.MERGE_TEST_AT
     *        FROM MERGE_TEST_SOURCE
     * ) as sub ON MERGE_TEST_DEST.MERGE_TEST_KEY = sub.MERGE_TEST_KEY
     * WHEN NOT MATCHED AND (sub.MERGE_TEST_VALUE = 1) THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, sub.MERGE_TEST_VALUE, 'one')
     * WHEN NOT MATCHED AND (sub.MERGE_TEST_VALUE = 2) THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, sub.MERGE_TEST_VALUE, 'two')
     * WHEN NOT MATCHED THEN
     *      INSERT (MERGE_TEST_AT, MERGE_TEST_KEY, MERGE_TEST_VALUE, MERGE_TEST_OPTIONAL_VALUE)
     *      VALUES ('2000-01-01T00:00:00', sub.MERGE_TEST_KEY, sub.MERGE_TEST_VALUE, 'three-and-more')
     * WHEN MATCHED AND (sub.MERGE_TEST_VALUE = 1) THEN
     *      DELETE
     * WHEN MATCHED AND (sub.MERGE_TEST_VALUE = 1) THEN
     *      UPDATE SET MERGE_TEST_KEY=sub.MERGE_TEST_KEY,
     *                 MERGE_TEST_VALUE=((MERGE_TEST_DEST.MERGE_TEST_VALUE + sub.MERGE_TEST_VALUE) * 10)
     * WHEN MATCHED AND (sub.MERGE_TEST_VALUE = 2) THEN
     *      UPDATE SET MERGE_TEST_KEY=sub.MERGE_TEST_KEY,
     *                 MERGE_TEST_VALUE=((MERGE_TEST_DEST.MERGE_TEST_VALUE + sub.MERGE_TEST_VALUE) * 100)
     * WHEN MATCHED AND (sub.MERGE_TEST_VALUE = 3) THEN
     *      DELETE
     * WHEN MATCHED THEN
     *      UPDATE SET MERGE_TEST_KEY=sub.MERGE_TEST_KEY,
     *                 MERGE_TEST_VALUE=1000
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `mergeFrom with multiple clauses`(testDb: TestDB) {
        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            dest.mergeFrom(sourceQuery, on = { defaultOnCondition() }) {
                whenNotMatchedInsert(and = (sourceQuery[source.value] eq 1)) {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = sourceQuery[source.value]
                    it[dest.optional] = "one"
                }
                whenNotMatchedInsert(and = (sourceQuery[source.value] eq 2)) {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = sourceQuery[source.value]
                    it[dest.optional] = "two"
                }
                whenNotMatchedInsert {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = sourceQuery[source.value]
                    it[dest.optional] = "three-and-more"
                }

                whenMatchedDelete(and = (sourceQuery[source.value] eq 1))
                whenMatchedUpdate(and = (sourceQuery[source.value] eq 1)) {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = (dest.value + sourceQuery[source.value]) * 10
                }
                whenMatchedUpdate(and = (sourceQuery[source.value] eq 2)) {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = (dest.value + sourceQuery[source.value]) * 100
                }
                whenMatchedDelete(and = (sourceQuery[source.value] eq 3))

                whenMatchedUpdate {
                    it[dest.key] = sourceQuery[source.key]
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
     * MergeFrom with whenNotMatchedDoNothing
     *
     * ```sql
     * MERGE INTO merge_test_dest
     * USING (
     *      SELECT merge_test_source.id,
     *             merge_test_source.merge_test_key,
     *             merge_test_source.merge_test_value,
     *             merge_test_source.merge_test_optional_value,
     *             merge_test_source.merge_test_at
     *        FROM merge_test_source
     * ) as sub ON merge_test_dest.merge_test_key = sub.merge_test_key
     * WHEN NOT MATCHED AND (sub.merge_test_value > 1) THEN DO NOTHING
     * WHEN NOT MATCHED THEN
     *      INSERT (merge_test_at, merge_test_key, merge_test_value)
     *      VALUES ('2000-01-01T00:00:00', sub.merge_test_key, (sub.merge_test_value + 100))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `do nothing in postgres`(testDb: TestDB) {
        Assumptions.assumeTrue(testDb in TestDB.ALL_POSTGRES)

        withMergeTestTablesAndDefaultData(testDb) { dest, source ->
            dest.mergeFrom(sourceQuery, on = { defaultOnCondition() }) {
                whenNotMatchedDoNothing(and = sourceQuery[source.value] greater 1)
                whenNotMatchedInsert {
                    it[dest.key] = sourceQuery[source.key]
                    it[dest.value] = sourceQuery[source.value] + 100
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
    fun `mergeFro with const condition`(testDb: TestDB) {
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
}
