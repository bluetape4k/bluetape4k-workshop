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
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
     *  WHERE (Cities.city_id < 51) AND (Cities.city_id > 0)
     *  ORDER BY Cities.city_id ASC
     *  LIMIT 25;
     *
     * SELECT Cities.city_id, Cities.`name`
     *   FROM Cities
     *  WHERE (Cities.city_id < 51) AND (Cities.city_id > 25)
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
     *  WHERE (cities.city_id < 51) AND (cities.city_id > 0)
     *  ORDER BY cities.city_id ASC
     *  LIMIT 25;
     *
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id < 51) AND (cities.city_id > 25)
     *  ORDER BY cities.city_id ASC
     *  LIMIT 25;
     *
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id < 51) AND (cities.city_id > 50)
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
     *
     * Postgres:
     * ```sql
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE cities.city_id < 51
     *  ORDER BY cities.city_id DESC
     *  LIMIT 25;
     *
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id < 51) AND (cities.city_id < 26)
     *  ORDER BY cities.city_id DESC
     *  LIMIT 25;
     *
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id < 51) AND (cities.city_id < 1)
     *  ORDER BY cities.city_id DESC
     *  LIMIT 25;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `when sortOrder is given, fetchBatchedResults should return batches in the given order`(testDB: TestDB) {
        val cities = DMLTestData.Cities
        withTables(testDB, cities) {
            val names = List(100) { TimebasedUuid.Epoch.nextIdAsString() }
            cities.batchInsert(names) { name ->
                this[cities.name] = name
            }

            val batches = cities.selectAll().where { cities.id less 51 }
                .fetchBatchedResults(batchSize = BATCH_SIZE, sortOrder = SortOrder.DESC)
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
     * Postgres:
     * ```sql
     * SELECT cities.city_id,
     *        cities."name"
     *   FROM cities
     *  WHERE TRUE AND (cities.city_id > 0)
     *  ORDER BY cities.city_id ASC
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
            cities.batchInsert(names) { name ->
                this[cities.name] = name
            }

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
     * Postgres:
     * ```sql
     * SELECT cities.city_id,
     *        cities."name"
     *   FROM cities
     *  WHERE TRUE
     *    AND (cities.city_id > 0)
     *  ORDER BY cities.city_id ASC
     *  LIMIT 100
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `when there are no items, fetchBatchedResults should return an empty list`(testDB: TestDB) {
        val cities = DMLTestData.Cities
        val users = DMLTestData.Users
        withTables(testDB, cities, users) {
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
     * Postgres:
     * ```sql
     * SELECT cities.city_id, cities."name"
     *   FROM cities
     *  WHERE (cities.city_id > 50) AND (cities.city_id > 0)
     *  ORDER BY cities.city_id ASC
     *  LIMIT 100
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `when there are no items of the given condition, should return an empty iterable`(testDB: TestDB) {
        val cities = DMLTestData.Cities
        val users = DMLTestData.Users
        withTables(testDB, cities, users) {
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
     * Postgres:
     * ```
     * CREATE TABLE IF NOT EXISTS table_1 (
     *      id SERIAL PRIMARY KEY,
     *      "data" VARCHAR(255) NOT NULL
     * );
     *
     * CREATE TABLE IF NOT EXISTS table_2 (
     *      id SERIAL PRIMARY KEY,
     *      more_data VARCHAR(100) NOT NULL,
     *      prev_data INT NOT NULL,
     *
     *      CONSTRAINT fk_table_2_prev_data__id FOREIGN KEY (prev_data) REFERENCES table_1(id)
     *          ON DELETE RESTRICT ON UPDATE CASCADE
     * );
     * ```
     *
     * ```sql
     * SELECT table_2.id,
     *        table_2.more_data,
     *        table_2.prev_data,
     *        table_1.id,
     *        table_1."data"
     *   FROM table_2 INNER JOIN table_1 ON table_1.id = table_2.prev_data
     *  WHERE TRUE
     *    AND (table_2.id > 0)
     *  ORDER BY table_2.id ASC
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
     * Alias 를 사용하여 `fetchBatchedResults` 함수를 사용할 수 있다.
     *
     * Postgres:
     * ```sql
     * SELECT tester_alias.id, tester_alias."name"
     *   FROM tester tester_alias
     *  WHERE TRUE AND (tester_alias.id > 0)
     *  ORDER BY tester_alias.id ASC
     *  LIMIT 1;
     *
     * SELECT tester_alias.id, tester_alias."name"
     *   FROM tester tester_alias
     *  WHERE TRUE AND (tester_alias.id > 1)
     *  ORDER BY tester_alias.id ASC
     *  LIMIT 1;
     *
     * SELECT tester_alias.id, tester_alias."name"
     *   FROM tester tester_alias
     *  WHERE TRUE AND (tester_alias.id > 2)
     *  ORDER BY tester_alias.id ASC
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
