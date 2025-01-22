package io.bluetape4k.workshop.exposed.domain.mysql

import com.mysql.cj.conf.PropertyKey.rewriteBatchedStatements
import com.mysql.cj.jdbc.ConnectionImpl
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.MYSQL_V8
import io.bluetape4k.workshop.exposed.domain.shared.dml.DMLTestData
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainAll
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.Query.CommentPosition.AFTER_SELECT
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.function
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MysqlTest: AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `embedded connection`() {
        Assumptions.assumeTrue { TestDB.MYSQL_V8 in TestDB.enabledDialects() }

        withDb(MYSQL_V8) {
            val version = TransactionManager.current().exec("SELECT VERSION();") {
                it.next()
                it.getString(1)
            }
            version!!.shouldNotBeEmpty()
        }
    }

    @Test
    fun `batch insert with rewrite batched statements on`() {
        Assumptions.assumeTrue { TestDB.MYSQL_V8 in TestDB.enabledDialects() }

        val cities = DMLTestData.Cities
        withTables(MYSQL_V8, cities) {
            /**
             * jdbc url 에 rewriteBatchedStatements=true 옵션을 추가하는 방식을 프로그래밍 방식으로 표현한 것입니다.
             *
             * jdbc url: `jdbc:mysql://localhost:3306/exposed?rewriteBatchedStatements=true`
             */
            /**
             * jdbc url 에 rewriteBatchedStatements=true 옵션을 추가하는 방식을 프로그래밍 방식으로 표현한 것입니다.
             *
             * jdbc url: `jdbc:mysql://localhost:3306/exposed?rewriteBatchedStatements=true`
             */
            val mysqlConn = this.connection.connection as ConnectionImpl
            mysqlConn.propertySet.getBooleanProperty(rewriteBatchedStatements).value = true

            val cityNames = listOf("FooCity", "BarCity")
            val generatedValues = cities.batchInsert(cityNames) { city ->
                this[cities.name] = city
            }

            generatedValues shouldHaveSize cityNames.size
            generatedValues.all { it.getOrNull(cities.id) != null }.shouldBeTrue()
        }
    }

    private class IndexHintQuery(
        val source: Query,
        val indexHint: String,
    ): Query(source.set, source.where) {

        init {
            source.copyTo(this)
        }

        override fun prepareSQL(builder: QueryBuilder): String {
            val originalSql = super.prepareSQL(builder)
            val fromTableSql = " FROM ${transaction.identity(set.source as Table)} "
            return originalSql.replace(fromTableSql, "$fromTableSql$indexHint ")
        }

        override fun copy(): IndexHintQuery =
            IndexHintQuery(source.copy(), indexHint).also { copy -> copyTo(copy) }
    }

    private fun Query.indexHint(hint: String): IndexHintQuery = IndexHintQuery(this, hint)

    @Test
    fun `custom select query with hint`() {
        Assumptions.assumeTrue { TestDB.MYSQL_V8 in TestDB.enabledDialects() }

        val tester = object: IntIdTable("tester") {
            val item = varchar("item", 32).uniqueIndex()
            val amount = integer("amount")
        }

        withTables(MYSQL_V8, tester) {
            val originalText = "Original SQL"
            val originalQuery = tester.selectAll()
                .withDistinct()
                .where { tester.id eq 2 }
                .limit(1)
                .comment(originalText)

            /**
             * 강제로 PRIMARY 인덱스를 사용하도록 hint를 추가합니다.
             *
             * ```sql
             * /*Original SQL*/
             * SELECT DISTINCT tester.id, tester.item, tester.amount
             *   FROM tester FORCE INDEX (PRIMARY)
             *  WHERE tester.id = ?
             *  LIMIT 1
             * ```
             *
             */
            /**
             * 강제로 PRIMARY 인덱스를 사용하도록 hint를 추가합니다.
             *
             * ```sql
             * /*Original SQL*/
             * SELECT DISTINCT tester.id, tester.item, tester.amount
             *   FROM tester FORCE INDEX (PRIMARY)
             *  WHERE tester.id = ?
             *  LIMIT 1
             * ```
             *
             */
            val hint1 = "FORCE INDEX (PRIMARY)"
            val hintQuery1 = originalQuery.indexHint(hint1)
            val hintQuery1Sql = hintQuery1.prepareSQL(this)

            log.debug { "Hint query 1: $hintQuery1Sql" }
            hintQuery1Sql shouldContainAll listOf(originalText, hint1, " WHERE ", " DISTINCT ", " LIMIT ")

            hintQuery1.toList()

            /**
             * 강제로 item 인덱스를 사용하도록 hint를 추가합니다.
             *
             * ```sql
             * SELECT tester.id,
             *        tester.item,
             *        tester.amount
             *   FROM tester USE INDEX (tester_item_unique)
             *  WHERE (tester.id = ?) AND (tester.item = ?)
             *  GROUP BY tester.id
             * HAVING COUNT(tester.id) = ?
             *  ORDER BY tester.amount ASC
             * ```
             */
            /**
             * 강제로 item 인덱스를 사용하도록 hint를 추가합니다.
             *
             * ```sql
             * SELECT tester.id,
             *        tester.item,
             *        tester.amount
             *   FROM tester USE INDEX (tester_item_unique)
             *  WHERE (tester.id = ?) AND (tester.item = ?)
             *  GROUP BY tester.id
             * HAVING COUNT(tester.id) = ?
             *  ORDER BY tester.amount ASC
             * ```
             */
            val itemIndex = tester.indices.first { it.columns == listOf(tester.item) }.indexName
            val hint2 = "USE INDEX ($itemIndex)"
            val hintQuery2 = tester
                .selectAll()
                .indexHint(hint2)
                .where { (tester.id eq 1) and (tester.item eq "Item A") }
                .groupBy(tester.id)
                .having { tester.id.count() eq 1 }
                .orderBy(tester.amount)
            val hintQuery2Sql = hintQuery2.prepareSQL(this)

            log.debug { "Hint query 2: $hintQuery2Sql" }
            hintQuery2Sql shouldContainAll listOf(hint2, " WHERE ", " GROUP BY ", " HAVING ", " ORDER BY ")

            hintQuery2.toList()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select with optimizer hint comment`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_MYSQL }

        val tester = object: Table("tester") {
            val seconds = integer("seconds")
        }

        withTables(testDB, tester) {
            tester.insert { it[seconds] = 1 }

            /**
             * SLEEP(N) 함수는 N초 동안 실행을 일시 중지하고, 중단되지 않으면 0을 반환합니다.
             *
             * ```sql
             * SELECT SLEEP(tester.seconds) FROM tester
             * ```
             */
            /**
             * SLEEP(N) 함수는 N초 동안 실행을 일시 중지하고, 중단되지 않으면 0을 반환합니다.
             *
             * ```sql
             * SELECT SLEEP(tester.seconds) FROM tester
             * ```
             */
            val sleepNSeconds: CustomFunction<Int?> = tester.seconds.function("SLEEP")
            val queryWithoutHint = tester.select(sleepNSeconds)
            queryWithoutHint.single()[sleepNSeconds] shouldBeEqualTo 0

            log.debug { "Query without hint: ${queryWithoutHint.prepareSQL(this)}" }

            tester.update { it[seconds] = 2 }

            /**
             * Hint places a limit of N milliseconds on how long a query should take before termination
             *
             * ```sql
             * SELECT /*+ MAX_EXECUTION_TIME(1000)*/ SLEEP(tester.seconds) FROM tester
             * ```
             */
            /**
             * Hint places a limit of N milliseconds on how long a query should take before termination
             *
             * ```sql
             * SELECT /*+ MAX_EXECUTION_TIME(1000)*/ SLEEP(tester.seconds) FROM tester
             * ```
             */
            val queryWithHint = tester
                .select(sleepNSeconds)
                .comment("+ MAX_EXECUTION_TIME(1000)", AFTER_SELECT)

            log.debug { "Query with hint: ${queryWithHint.prepareSQL(this)}" }

            if (testDB in TestDB.ALL_MYSQL) {
                // Query execution was interrupted, max statement execution time exceeded
                expectException<ExposedSQLException> {
                    queryWithHint.single()
                }
            } else {
                // MariaDB has much fewer optimizer hint options and, like any other db, will just ignore the comment
                queryWithHint.single()[sleepNSeconds] shouldBeEqualTo 0
            }
        }
    }

}
