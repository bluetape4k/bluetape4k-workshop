package io.bluetape4k.workshop.exposed.sql.kotlin.datetime

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.dateLiteral
import org.jetbrains.exposed.v1.datetime.dateTimeLiteral
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.datetime.timestampLiteral
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * DATE, DATEIME 용 리터럴을 사용한 테스트
 */
class DateTimeLiteralTest: AbstractExposedTest() {

    private val defaultDate = LocalDate(2000, 1, 1)
    private val futureDate = LocalDate(3000, 1, 1)

    object TableWithDate: IntIdTable() {
        val date = date("date")
    }

    private val defaultDatetime = LocalDateTime(2000, 1, 1, 8, 0, 0, 100000000)
    private val futureDatetime = LocalDateTime(3000, 1, 1, 8, 0, 0, 100000000)

    /**
     * ```sql
     * -- Postgres
     * CREATE TABLE IF NOT EXISTS tablewithdatetime (
     *      id SERIAL PRIMARY KEY,
     *      datetime TIMESTAMP NOT NULL
     * )
     * ```
     * ```sql
     * -- MySQL V8
     * CREATE TABLE IF NOT EXISTS TableWithDatetime (
     *      id INT AUTO_INCREMENT PRIMARY KEY,
     *      datetime DATETIME(6) NOT NULL
     * )
     * ```
     */
    object TableWithDatetime: IntIdTable() {
        val datetime = datetime("datetime")
    }

    private val defaultTimestamp = Instant.parse("2000-01-01T01:00:00.00Z")
    private val futureTimestamp = Instant.parse("3000-01-01T01:00:00.00Z")

    object TableWithTimestamp: IntIdTable() {
        val timestamp = timestamp("timestamp")
    }

    /**
     * [dateLiteral] 을 사용한 날짜 등가 테스트
     *
     * ```sql
     * -- Postgres
     * SELECT tablewithdate."date"
     *   FROM tablewithdate
     *  WHERE tablewithdate."date" = '2000-01-01'
     * ```
     * ```sql
     * -- MySQL V8
     * SELECT TableWithDate.`date`
     *   FROM TableWithDate
     *  WHERE TableWithDate.`date` = '2000-01-01'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByDateLiteralEquality(testDB: TestDB) {
        withTables(testDB, TableWithDate) {
            TableWithDate.insert {
                it[date] = defaultDate
            }

            val query = TableWithDate
                .select(TableWithDate.date)
                .where {
                    TableWithDate.date eq dateLiteral(defaultDate)
                }
            query.single()[TableWithDate.date] shouldBeEqualTo defaultDate
        }
    }

    /**
     * [dateLiteral] 을 사용한 날짜 비교 테스트
     *
     * ```sql
     * -- Postgres
     * SELECT tablewithdate.id, tablewithdate."date"
     *   FROM tablewithdate
     *  WHERE tablewithdate."date" < '3000-01-01';
     * ```
     * ```sql
     * -- MySQL V8
     * SELECT TableWithDate.id, TableWithDate.`date`
     *   FROM TableWithDate
     *  WHERE TableWithDate.`date` < '3000-01-01'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByDateLiteralComparison(testDB: TestDB) {
        withTables(testDB, TableWithDate) {
            TableWithDate.insert {
                it[date] = defaultDate
            }
            val query = TableWithDate.selectAll()
                .where {
                    TableWithDate.date less dateLiteral(futureDate)
                }
            query.firstOrNull().shouldNotBeNull()
        }
    }

    /**
     * [dateTimeLiteral] 을 사용한 날짜시간 비교 테스트
     *
     * ```sql
     * -- Postgres
     * SELECT tablewithdatetime.datetime
     *   FROM tablewithdatetime
     *  WHERE tablewithdatetime.datetime = '2000-01-01T08:00:00.1';
     * ```
     * ```sql
     * -- MySQL V8
     * SELECT TableWithDatetime.datetime
     *   FROM TableWithDatetime
     *  WHERE TableWithDatetime.datetime = '2000-01-01 08:00:00.100000';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByDatetimeLiteralEquality(testDB: TestDB) {
        withTables(testDB, TableWithDatetime) {
            TableWithDatetime.insert {
                it[datetime] = defaultDatetime
            }

            val query = TableWithDatetime
                .select(TableWithDatetime.datetime)
                .where {
                    TableWithDatetime.datetime eq dateTimeLiteral(defaultDatetime)
                }
            query.single()[TableWithDatetime.datetime] shouldBeEqualTo defaultDatetime
        }
    }

    /**
     * [dateTimeLiteral] 을 사용한 날짜시간 비교 테스트
     *
     * ```sql
     * -- Postgres
     * SELECT tablewithdatetime.id, tablewithdatetime.datetime
     *   FROM tablewithdatetime
     *  WHERE tablewithdatetime.datetime < '3000-01-01T08:00:00.1'
     * ```
     * ```sql
     * -- MySQL V8
     * SELECT TableWithDatetime.id, TableWithDatetime.datetime
     *   FROM TableWithDatetime
     *  WHERE TableWithDatetime.datetime < '3000-01-01 08:00:00.100000'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByDatetimeLiteralComparison(testDB: TestDB) {
        withTables(testDB, TableWithDatetime) {
            TableWithDatetime.insert {
                it[datetime] = defaultDatetime
            }
            val query = TableWithDatetime
                .selectAll()
                .where {
                    TableWithDatetime.datetime less dateTimeLiteral(futureDatetime)
                }
            query.firstOrNull().shouldNotBeNull()
        }
    }

    /**
     * [timestampLiteral] 을 사용한 타임스탬프 등가 테스트
     *
     * ```sql
     * -- Postgres
     * SELECT tablewithtimestamp."timestamp"
     *   FROM tablewithtimestamp
     *  WHERE tablewithtimestamp."timestamp" = '2000-01-01T01:00:00'
     * ```
     * ```sql
     * -- MySQL V8
     * SELECT TableWithTimestamp.`timestamp`
     *   FROM TableWithTimestamp
     *  WHERE TableWithTimestamp.`timestamp` = '2000-01-01 01:00:00.000000'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByTimestampLiteralEquality(testDB: TestDB) {
        withTables(testDB, TableWithTimestamp) {
            TableWithTimestamp.insert {
                it[timestamp] = defaultTimestamp
            }

            val query = TableWithTimestamp
                .select(TableWithTimestamp.timestamp)
                .where {
                    TableWithTimestamp.timestamp eq timestampLiteral(defaultTimestamp)
                }
            query.single()[TableWithTimestamp.timestamp] shouldBeEqualTo defaultTimestamp
        }
    }

    /**
     * [timestampLiteral] 을 사용한 타임스탬프 비교 테스트
     *
     * ```sql
     * -- Postgres
     * SELECT tablewithtimestamp."timestamp"
     *   FROM tablewithtimestamp
     *  WHERE tablewithtimestamp."timestamp" < '3000-01-01T01:00:00'
     * ```
     * ```sql
     * -- MySQL V8
     * SELECT TableWithTimestamp.`timestamp`
     *   FROM TableWithTimestamp
     *  WHERE TableWithTimestamp.`timestamp` < '3000-01-01 01:00:00.000000'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByTimestampLiteralComparison(testDB: TestDB) {
        withTables(testDB, TableWithTimestamp) {
            TableWithTimestamp.insert {
                it[timestamp] = defaultTimestamp
            }

            val query = TableWithTimestamp
                .select(TableWithTimestamp.timestamp)
                .where {
                    TableWithTimestamp.timestamp less timestampLiteral(futureTimestamp)
                }
            query.single()[TableWithTimestamp.timestamp] shouldBeEqualTo defaultTimestamp
        }
    }
}
