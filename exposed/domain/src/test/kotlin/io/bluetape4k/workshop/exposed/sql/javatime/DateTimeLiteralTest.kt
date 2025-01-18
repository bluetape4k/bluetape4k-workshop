package io.bluetape4k.workshop.exposed.sql.javatime

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.dateLiteral
import org.jetbrains.exposed.sql.javatime.dateTimeLiteral
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.timestampLiteral
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime


class DateTimeLiteralTest: AbstractExposedTest() {

    private val defaultDate = LocalDate.of(2000, 1, 1)
    private val futureDate = LocalDate.of(3000, 1, 1)

    object TableWithDate: IntIdTable("TableWithDate") {
        val date = date("date")
    }

    private val defaultDateTime = LocalDateTime.of(2000, 1, 1, 8, 0, 0, 100000000)
    private val futureDatetime = LocalDateTime.of(3000, 1, 1, 8, 0, 0, 100000000)

    object TableWithDateTime: IntIdTable("TableWithDateTime") {
        val dateTime = datetime("datetime")
    }

    private val defaultTimestamp = Instant.parse("2000-01-01T01:00:00.00Z")

    object TableWithTimestamp: IntIdTable("TableWithTimestamp") {
        val timestamp = timestamp("timestamp")
    }

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
}
