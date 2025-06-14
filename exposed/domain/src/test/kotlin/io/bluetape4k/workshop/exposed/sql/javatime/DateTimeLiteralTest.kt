package io.bluetape4k.workshop.exposed.sql.javatime

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.dateLiteral
import org.jetbrains.exposed.v1.javatime.dateTimeLiteral
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.javatime.timestampLiteral
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime


class DateTimeLiteralTest: AbstractExposedTest() {

    private val defaultDate = LocalDate.of(2000, 1, 1)
    private val futureDate = LocalDate.of(3000, 1, 1)

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS tablewithdate (
     *      id SERIAL PRIMARY KEY,
     *      "date" DATE NOT NULL
     * )
     * ```
     */
    object TableWithDate: IntIdTable("TableWithDate") {
        val date = date("date")
    }

    private val defaultDateTime = LocalDateTime.of(2000, 1, 1, 8, 0, 0, 100000000)
    private val futureDatetime = LocalDateTime.of(3000, 1, 1, 8, 0, 0, 100000000)

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS tablewithdatetime (
     *      id SERIAL PRIMARY KEY,
     *      datetime TIMESTAMP NOT NULL
     * );
     * ```
     */
    object TableWithDateTime: IntIdTable("TableWithDateTime") {
        val dateTime = datetime("datetime")
    }

    private val defaultTimestamp = Instant.parse("2000-01-01T01:00:00.00Z")
    private val futureTimestamp = Instant.parse("3000-01-01T01:00:00.00Z")

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS tablewithtimestamp (
     *      id SERIAL PRIMARY KEY,
     *      "timestamp" TIMESTAMP NOT NULL
     * )
     * ```
     */
    object TableWithTimestamp: IntIdTable("TableWithTimestamp") {
        val timestamp = timestamp("timestamp")
    }

    /**
     * `dateLiteral` 을 이용하여 검색하기
     *
     * Postgres:
     * ```sql
     * INSERT INTO tablewithdate ("date") VALUES ('2000-01-01');
     *
     * SELECT tablewithdate."date"
     *   FROM tablewithdate
     *  WHERE tablewithdate."date" = '2000-01-01';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select by date literal equality`(testDB: TestDB) {
        withTables(testDB, TableWithDate) {
            TableWithDate.insert {
                it[date] = defaultDate
            }
            val query = TableWithDate.select(TableWithDate.date)
                .where { TableWithDate.date eq dateLiteral(defaultDate) }
            query.single()[TableWithDate.date] shouldBeEqualTo defaultDate
        }
    }

    /**
     * `dateLiteral` 을 이용하여 비교하기
     *
     * Postgres:
     * ```sql
     * INSERT INTO tablewithdate ("date") VALUES ('2000-01-01');
     *
     * SELECT tablewithdate."date"
     *   FROM tablewithdate
     *  WHERE tablewithdate."date" < '3000-01-01';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select by Date Literal Comparison`(testDB: TestDB) {
        withTables(testDB, TableWithDate) {
            TableWithDate.insert {
                it[date] = defaultDate
            }
            val query = TableWithDate.selectAll()
                .where { TableWithDate.date less dateLiteral(futureDate) }
            query.firstOrNull().shouldNotBeNull()
        }
    }

    /**
     * `dateTimeLiteral` 을 이용하여 검색하기
     *
     * Postgres:
     * ```sql
     * INSERT INTO tablewithdatetime (datetime) VALUES ('2000-01-01T08:00:00.1');
     *
     * SELECT tablewithdatetime.datetime
     *   FROM tablewithdatetime
     *  WHERE tablewithdatetime.datetime = '2000-01-01T08:00:00.1';
     * ```
     *
     * MySQL V8:
     * ```sql
     * INSERT INTO TableWithDateTime (datetime) VALUES ('2000-01-01 08:00:00.100000');
     *
     * SELECT TableWithDateTime.datetime
     *   FROM TableWithDateTime
     *  WHERE TableWithDateTime.datetime = '2000-01-01 08:00:00.100000';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select By DateTime Literal Equality`(testDB: TestDB) {
        withTables(testDB, TableWithDateTime) {
            TableWithDateTime.insert {
                it[dateTime] = defaultDateTime
            }
            val query = TableWithDateTime.select(TableWithDateTime.dateTime)
                .where { TableWithDateTime.dateTime eq dateTimeLiteral(defaultDateTime) }
            query.single()[TableWithDateTime.dateTime] shouldBeEqualTo defaultDateTime
        }
    }

    /**
     * `dateTimeLiteral` 을 이용하여 비교 검색하기
     *
     * Postgres:
     * ```sql
     * INSERT INTO tablewithdatetime (datetime) VALUES ('2000-01-01T08:00:00.1');
     *
     * SELECT tablewithdatetime.datetime
     *   FROM tablewithdatetime
     *  WHERE tablewithdatetime.datetime < '3000-01-01T08:00:00.1';
     * ```
     *
     * MySQL V8:
     * ```sql
     * INSERT INTO TableWithDateTime (datetime) VALUES ('2000-01-01 08:00:00.100000');
     *
     * SELECT TableWithDateTime.datetime
     *   FROM TableWithDateTime
     *  WHERE TableWithDateTime.datetime < '3000-01-01 08:00:00.100000';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select By DateTime Literal Comparison`(testDB: TestDB) {
        withTables(testDB, TableWithDateTime) {
            TableWithDateTime.insert {
                it[dateTime] = defaultDateTime
            }
            val query = TableWithDateTime.selectAll()
                .where { TableWithDateTime.dateTime less dateTimeLiteral(futureDatetime) }
            query.firstOrNull().shouldNotBeNull()
        }
    }

    /**
     * `timestampLiteral` 을 이용하여 검색하기
     *
     * Postgres:
     * ```sql
     * INSERT INTO tablewithtimestamp ("timestamp") VALUES ('2000-01-01T01:00:00');
     *
     * SELECT tablewithtimestamp."timestamp"
     *   FROM tablewithtimestamp
     *  WHERE tablewithtimestamp."timestamp" = '2000-01-01T01:00:00';
     * ```
     * MySQL V8:
     * ```sql
     * INSERT INTO TableWithTimestamp (`timestamp`) VALUES ('2000-01-01 01:00:00.000000');
     *
     * SELECT TableWithTimestamp.`timestamp`
     *   FROM TableWithTimestamp
     *  WHERE TableWithTimestamp.`timestamp` = '2000-01-01 01:00:00.000000';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select By Timestamp Literal Equality`(testDB: TestDB) {
        withTables(testDB, TableWithTimestamp) {
            TableWithTimestamp.insert {
                it[timestamp] = defaultTimestamp
            }
            val query = TableWithTimestamp.select(TableWithTimestamp.timestamp)
                .where { TableWithTimestamp.timestamp eq timestampLiteral(defaultTimestamp) }

            query.single()[TableWithTimestamp.timestamp] shouldBeEqualTo defaultTimestamp
        }
    }

    /**
     * `timestampLiteral` 을 이용하여 비교 검색하기
     *
     * Postgres:
     * ```sql
     * INSERT INTO tablewithtimestamp ("timestamp") VALUES ('2000-01-01T01:00:00');
     *
     * SELECT tablewithtimestamp."timestamp"
     *   FROM tablewithtimestamp
     *  WHERE tablewithtimestamp."timestamp" < '3000-01-01T01:00:00';
     * ```
     * MySQL V8:
     * ```sql
     * INSERT INTO TableWithTimestamp (`timestamp`) VALUES ('2000-01-01 01:00:00.000000');
     *
     * SELECT TableWithTimestamp.`timestamp`
     *   FROM TableWithTimestamp
     *  WHERE TableWithTimestamp.`timestamp` < '3000-01-01 01:00:00.000000';
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select By Timestamp Literal Comparison`(testDB: TestDB) {
        withTables(testDB, TableWithTimestamp) {
            TableWithTimestamp.insert {
                it[timestamp] = defaultTimestamp
            }
            val query = TableWithTimestamp.select(TableWithTimestamp.timestamp)
                .where { TableWithTimestamp.timestamp less timestampLiteral(futureTimestamp) }

            query.firstOrNull().shouldNotBeNull()
        }
    }

}
