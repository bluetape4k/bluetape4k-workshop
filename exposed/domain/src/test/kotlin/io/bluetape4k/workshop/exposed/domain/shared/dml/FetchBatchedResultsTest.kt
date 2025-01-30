package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.idgenerators.uuid.TimebasedUuid.Epoch
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.dml.DMLTestData.UserData
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SortOrder.DESC
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*

/**
 * 배치 사이즈에 맞게 결과를 가져오는 기능을 제공하는 `fetchBatchedResults` 함수를 테스트합니다.
 *
 * **단, `autoinc` 컬럼이 없는 테이블에 대해서는 사용할 수 없습니다.**
 */
class FetchBatchedResultsTest: AbstractExposedTest() {

    companion object: KLogging() {
        private const val BATCH_SIZE = 25
    }

    /**
     * Fetch Batched Results with where and set batchSize
     *
     * MySQL:
     * ```sql
     * SELECT Cities.city_id, Cities.`name`
     *   FROM Cities
     *  WHERE (Cities.city_id < 51)
     *    AND (Cities.city_id > 0)
     *  ORDER BY Cities.city_id ASC
     *  LIMIT 25;
     *
     * SELECT Cities.city_id, Cities.`name`
     *   FROM Cities
     *  WHERE (Cities.city_id < 51)
     *    AND (Cities.city_id > 25)
     *  ORDER BY Cities.city_id ASC
     *  LIMIT 25;
     *
     * SELECT Cities.city_id, Cities.`name`
     *   FROM Cities
     *  WHERE (Cities.city_id < 51) AND (Cities.city_id > 50)
     *  ORDER BY Cities.city_id ASC
     *  LIMIT 25;
     * ```
     *
     * Postgres:
     * ```sql
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id < 51)
     *    AND (cities.city_id > 0)
     *  ORDER BY cities.city_id ASC
     *  LIMIT 25;
     *
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id < 51)
     *    AND (cities.city_id > 25)
     *  ORDER BY cities.city_id ASC
     *  LIMIT 25;
     *
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id < 51)
     *    AND (cities.city_id > 50)
     *  ORDER BY cities.city_id ASC
     *  LIMIT 25;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `fetchBatchedResults with where and set batchSize`(testDB: TestDB) {
        val cities = DMLTestData.Cities

        withTables(testDB, cities) {
            // 100개의 도시 이름을 저장합니다.
            val names = List(100) { TimebasedUuid.Epoch.nextIdAsString() }
            cities.batchInsert(names) { name ->
                this[cities.name] = name
            }

            val batches = cities.selectAll().where { cities.id less 51 }
                .fetchBatchedResults(batchSize = BATCH_SIZE)
                .toList()
                .map { it.toCityNameList() }

            batches shouldHaveSize 2

            val expectedNames = names.take(50)
            batches shouldBeEqualTo listOf(
                expectedNames.take(BATCH_SIZE),
                expectedNames.takeLast(BATCH_SIZE)
            )

            batches.flatten() shouldBeEqualTo expectedNames
        }
    }

    /**
     * `fetchBatchedResults` 함수의 `sortOrder` 옵션을 이용하여 정렬할 수 있다. 
     * H2
     * ```sql
     * SELECT CITIES.CITY_ID, CITIES."name"
     *   FROM CITIES
     *  WHERE CITIES.CITY_ID < 51
     *  ORDER BY CITIES.CITY_ID DESC
     *  LIMIT 25;
     *
     * SELECT CITIES.CITY_ID, CITIES."name"
     *   FROM CITIES
     *  WHERE (CITIES.CITY_ID < 51) AND (CITIES.CITY_ID < 26)
     *  ORDER BY CITIES.CITY_ID DESC
     *  LIMIT 25;
     *
     * SELECT CITIES.CITY_ID, CITIES."name"
     *   FROM CITIES
     *  WHERE (CITIES.CITY_ID < 51) AND (CITIES.CITY_ID < 1)
     *  ORDER BY CITIES.CITY_ID DESC
     *  LIMIT 25
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `when sortOrder is given, fetchBatchedResults should return batches in the given order`(testDB: TestDB) {
        val cities = DMLTestData.Cities
        withTables(testDB, cities) {
            val names = List(100) { TimebasedUuid.Epoch.nextIdAsString() }
            cities.batchInsert(names) { name -> this[cities.name] = name }

            val batches = cities.selectAll().where { cities.id less 51 }
                .fetchBatchedResults(batchSize = BATCH_SIZE, sortOrder = DESC)
                .toList()
                .map { it.toCityNameList() }

            batches shouldHaveSize 2

            val expectedNames = names.take(50).reversed()
            batches shouldBeEqualTo listOf(
                expectedNames.take(BATCH_SIZE),
                expectedNames.takeLast(BATCH_SIZE)
            )

            batches.flatten() shouldBeEqualTo expectedNames
        }
    }

    /**
     * batchSize 가 전체 레코드 수보다 크면, 한 번에 모든 레코드를 가져온다.
     *
     * H2
     * ```sql
     * SELECT CITIES.CITY_ID, CITIES."name"
     *   FROM CITIES
     *  WHERE TRUE AND (CITIES.CITY_ID > 0)
     *  ORDER BY CITIES.CITY_ID ASC
     *  LIMIT 100
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `when batch size is greater than the amount of available items, fetchBatchedResults should return 1 batch`(
        testDB: TestDB,
    ) {
        val cities = DMLTestData.Cities
        withTables(testDB, cities) {
            val names = List(25) { Epoch.nextIdAsString() }
            cities.batchInsert(names) { name -> this[cities.name] = name }

            val batches = cities.selectAll()
                .fetchBatchedResults(batchSize = 100)
                .toList()
                .map { it.toCityNameList() }

            batches shouldHaveSize 1
            batches shouldBeEqualTo listOf(names)
        }
    }

    /**
     * 레코드가 없을 때, 빈 리스트를 반환한다.
     * 
     * H2
     * ```sql
     * SELECT CITIES.CITY_ID, CITIES."name"
     *   FROM CITIES
     *  WHERE TRUE AND (CITIES.CITY_ID > 0)
     *  ORDER BY CITIES.CITY_ID ASC
     *  LIMIT 100
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `when there are no items, fetchBatchedResults should return an empty list`(testDB: TestDB) {
        val cities = DMLTestData.Cities
        withTables(testDB, cities) {
            val batches = cities.selectAll()
                .fetchBatchedResults(batchSize = 100)
                .toList()
                .map { it.toCityNameList() }

            batches.shouldBeEmpty()
        }
    }

    /**
     * 조건에 맞는 레코드가 없을 때, 빈 리스트를 반환한다.
     *
     * ```sql
     * SELECT CITIES.CITY_ID, CITIES."name"
     *   FROM CITIES
     *  WHERE (CITIES.CITY_ID > 50)
     *    AND (CITIES.CITY_ID > 0)
     *  ORDER BY CITIES.CITY_ID ASC
     *  LIMIT 100
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `when there are no items of the given condition, should return an empty iterable`(testDB: TestDB) {
        val cities = DMLTestData.Cities
        withTables(testDB, cities) {
            val names = List(25) { UUID.randomUUID().toString() }
            cities.batchInsert(names) { name -> this[cities.name] = name }

            val batches = cities.selectAll().where { cities.id greater 50 }
                .fetchBatchedResults(batchSize = 100)
                .toList()
                .map { it.toCityNameList() }

            batches.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `autoinc 컬럼이 없으면 fetchBatchedResults를 사용할 수 없다`(testDB: TestDB) {
        withTables(testDB, UserData) {
            expectException<UnsupportedOperationException> {
                UserData.selectAll().fetchBatchedResults()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch size 가 0이거나 음수이면 fetchBatchedResults를 사용할 수 없다`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            expectException<IllegalArgumentException> {
                cities.selectAll().fetchBatchedResults(-1)
            }
        }
    }

    /**
     * Auto Increment EntityID 를 가진 테이블에 대해서 `fetchBatchedResults` 함수를 사용할 수 있다.
     *
     * H2
     * ```sql
     * SELECT TABLE_2.ID,
     *        TABLE_2.MORE_DATA,
     *        TABLE_2.PREV_DATA,
     *        TABLE_1.ID,
     *        TABLE_1."data"
     *   FROM TABLE_2 INNER JOIN TABLE_1 ON TABLE_1.ID = TABLE_2.PREV_DATA
     *  WHERE TRUE
     *    AND (TABLE_2.ID > 0)
     *  ORDER BY TABLE_2.ID ASC
     *  LIMIT 10000;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `fetchBatchedResults with auto increment EntityID`(testDB: TestDB) {
        val tester1 = object: IntIdTable("table_1") {
            val data = varchar("data", 255)
        }
        val tester2 = object: IntIdTable("table_2") {
            val moreData = varchar("more_data", 100)
            val prevData = reference("prev_data", tester1, onUpdate = ReferenceOption.CASCADE)
        }

        withTables(testDB, tester1, tester2) {
            val join = (tester2 innerJoin tester1)

            join.selectAll().fetchBatchedResults(10_000).flatten()
        }
    }

    /**
     * H2
     * ```sql
     * SELECT tester_alias.ID, tester_alias."name"
     *   FROM TESTER tester_alias
     *  WHERE TRUE AND (tester_alias.ID > 0)
     *  ORDER BY tester_alias.ID ASC
     *  LIMIT 1;
     *
     * SELECT tester_alias.ID, tester_alias."name"
     *   FROM TESTER tester_alias
     *  WHERE TRUE AND (tester_alias.ID > 1)
     *  ORDER BY tester_alias.ID ASC
     *  LIMIT 1;
     *
     * SELECT tester_alias.ID, tester_alias."name"
     *   FROM TESTER tester_alias
     *  WHERE TRUE AND (tester_alias.ID > 2)
     *  ORDER BY tester_alias.ID ASC
     *  LIMIT 1;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `fetchBatchedResults with alias`(testDB: TestDB) {
        val tester = object: IntIdTable("tester") {
            val name = varchar("name", 1)
        }

        withTables(testDB, tester) {
            tester.insert { it[name] = "a" }
            tester.insert { it[name] = "b" }

            tester.alias("tester_alias").selectAll().fetchBatchedResults(1).flatten() shouldHaveSize 2
        }
    }
}
