package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SortOrder.ASC
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DistinctOnTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * `DISTINCT ON` (`withDistinctOn`) 은 Postgres와 H2 에서민 지원됩니다.
     */
    private val distinctOnSupportedDb = TestDB.ALL_POSTGRES + TestDB.ALL_H2

    /**
     * `withDistinctOn` (`DISTINCT ON`) 은 Postgres와 H2 에서민 지원됩니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `distinctOn method`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in distinctOnSupportedDb }

        /**
         * H2
         * ```sql
         * CREATE TABLE IF NOT EXISTS TESTER (
         *      ID INT AUTO_INCREMENT PRIMARY KEY,
         *      V1 INT NOT NULL,
         *      V2 INT NOT NULL
         * );
         * ```
         */
        val tester = object: IntIdTable("tester") {
            val v1 = integer("v1")
            val v2 = integer("v2")
        }

        withTables(testDB, tester) {
            tester.batchInsert(
                listOf(
                    listOf(1, 1), listOf(1, 2), listOf(1, 2),
                    listOf(2, 1), listOf(2, 2), listOf(2, 2),
                    listOf(4, 4), listOf(4, 4), listOf(4, 4),
                )
            ) {
                this[tester.v1] = it[0]
                this[tester.v2] = it[1]
            }
            /**
             * `withDistinctOn` (`DISTINCT ON`) 은 Postgres와 H2 에서민 지원됩니다.
             *
             * H2:
             * ```sql
             * SELECT DISTINCT ON (TESTER.V1)
             *        TESTER.ID,
             *        TESTER.V1,
             *        TESTER.V2
             *   FROM TESTER
             *  ORDER BY TESTER.V1 ASC, TESTER.V2 ASC
             * ```
             *
             * Postgres:
             * ```sql
             * SELECT DISTINCT ON (tester.v1)
             *        tester.id,
             *        tester.v1,
             *        tester.v2
             *   FROM tester
             *  ORDER BY tester.v1 ASC, tester.v2 ASC
             *  ```
             */
            val distinctValue1 = tester.selectAll()
                .withDistinctOn(tester.v1)
                .orderBy(tester.v1 to ASC, tester.v2 to ASC)
                .map { it[tester.v1] to it[tester.v2] }

            distinctValue1 shouldBeEqualTo listOf(1 to 1, 2 to 1, 4 to 4)

            /**
             * DistinctOn is supported by Postgres and H2
             *
             * H2
             * ```sql
             * SELECT DISTINCT ON (TESTER.V2)
             *        TESTER.ID,
             *        TESTER.V1,
             *        TESTER.V2
             *   FROM TESTER
             *  ORDER BY TESTER.V2 ASC, TESTER.V1 ASC
             * ```
             *
             * Postgres
             * ```sql
             * SELECT DISTINCT ON (tester.v2)
             *        tester.id,
             *        tester.v1,
             *        tester.v2
             *   FROM tester
             *  ORDER BY tester.v2 ASC, tester.v1 ASC
             *  ```
             */
            val distinctValue2 = tester.selectAll()
                .withDistinctOn(tester.v2)
                .orderBy(tester.v2 to ASC, tester.v1 to ASC)
                .map { it[tester.v1] to it[tester.v2] }

            distinctValue2 shouldBeEqualTo listOf(1 to 1, 1 to 2, 4 to 4)

            /**
             * H2
             * ```sql
             * SELECT DISTINCT ON (TESTER.V1, TESTER.V2)
             *        TESTER.ID,
             *        TESTER.V1,
             *        TESTER.V2
             *   FROM TESTER
             *  ORDER BY TESTER.V1 ASC, TESTER.V2 ASC
             *  ```
             */
            val distinctBoth = tester.selectAll()
                .withDistinctOn(tester.v1, tester.v2)
                .orderBy(tester.v1 to ASC, tester.v2 to ASC)
                .map { it[tester.v1] to it[tester.v2] }

            distinctBoth shouldBeEqualTo listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2, 4 to 4)

            /**
             * H2
             * ```sql
             * SELECT DISTINCT ON (TESTER.V1, TESTER.V2)
             *        TESTER.ID,
             *        TESTER.V1,
             *        TESTER.V2
             *   FROM TESTER
             *  ORDER BY TESTER.V1 ASC, TESTER.V2 ASC
             * ```
             */
            val distinctSequential = tester.selectAll()
                .withDistinctOn(tester.v1 to ASC)
                .withDistinctOn(tester.v2 to ASC)
                .map { it[tester.v1] to it[tester.v2] }

            distinctSequential shouldBeEqualTo distinctBoth
        }
    }

    /**
     * `withDistinct` 와 `withDistinctOn` 을 동시에 사용하면 예외가 발생합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exception when distinct and distinctOn`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in distinctOnSupportedDb }

        /**
         * H2
         * ```sql
         * CREATE TABLE IF NOT EXISTS TESTER (
         *      ID INT AUTO_INCREMENT PRIMARY KEY,
         *      V1 INT NOT NULL
         * )
         * ```
         */
        val tester = object: IntIdTable("tester") {
            val v1 = integer("v1")
        }

        withTables(testDB, tester) {
            val query1 = tester.selectAll().withDistinct()
            expectException<IllegalArgumentException> {
                query1.withDistinctOn(tester.v1)
            }

            val query2 = tester.selectAll().withDistinctOn(tester.v1)
            expectException<IllegalArgumentException> {
                query2.withDistinct()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `empty distinctOn`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in distinctOnSupportedDb }

        /**
         * H2
         * ```sql
         * CREATE TABLE IF NOT EXISTS TESTER (
         *      ID INT AUTO_INCREMENT PRIMARY KEY,
         *      V1 INT NOT NULL
         * )
         * ```
         */
        val tester = object: IntIdTable("tester") {
            val v1 = integer("v1")
        }

        withTables(testDb, tester) {
            tester.insert {
                it[tester.v1] = 1
            }

            // `withDistinctOn` 에 컬럼을 지정하지 않으면 예외가 발생하지 않습니다.
            val query = tester.selectAll()
                .withDistinctOn(columns = emptyArray<Column<*>>())

            query.distinctOn.shouldBeNull()

            // SELECT TESTER.ID, TESTER.V1 FROM TESTER;  (DistinctOn is not used)
            val value = query.first()[tester.v1]
            value shouldBeEqualTo 1
        }
    }

    /**
     * `withDistinctOn` 을 사용한 쿼리에 대한 COUNT 쿼리를 실행합니다. (다른 DB에서는 group by 를 사용해야 합니다.)
     *
     * H2
     * ```sql
     * SELECT COUNT(*)
     *   FROM (
     *          SELECT DISTINCT ON (TESTER."name") TESTER."name" tester_name
     *            FROM TESTER
     *        ) subquery
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `distinctOn with count`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in distinctOnSupportedDb }

        val tester = object: IntIdTable("tester") {
            val name = varchar("name", 50)
        }
        withTables(testDb, tester) {
            val names = listOf("tester1", "tester1", "tester2", "tester3", "tester2")
            tester.batchInsert(names) {
                this[tester.name] = it
            }

            val count = tester.select(tester.name)
                .withDistinctOn(tester.name)
                .count()

            count shouldBeEqualTo names.distinct().size.toLong()  // 3L
        }
    }
}
