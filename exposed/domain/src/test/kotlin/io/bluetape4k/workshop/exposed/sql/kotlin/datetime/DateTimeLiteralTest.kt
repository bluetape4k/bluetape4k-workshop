package io.bluetape4k.workshop.exposed.sql.kotlin.datetime

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.dateLiteral
import org.jetbrains.exposed.sql.kotlin.datetime.dateTimeLiteral
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestampLiteral
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DateTimeLiteralTest: AbstractExposedTest() {

    private val defaultDate = LocalDate(2000, 1, 1)
    private val futureDate = LocalDate(3000, 1, 1)

    object TableWithDate: IntIdTable() {
        val date = date("date")
    }

    private val defaultDatetime = LocalDateTime(2000, 1, 1, 8, 0, 0, 100000000)
    private val futureDatetime = LocalDateTime(3000, 1, 1, 8, 0, 0, 100000000)

    object TableWithDatetime: IntIdTable() {
        val datetime = datetime("datetime")
    }

    private val defaultTimestamp = Instant.parse("2000-01-01T01:00:00.00Z")

    object TableWithTimestamp: IntIdTable() {
        val timestamp = timestamp("timestamp")
    }

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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByDatetimeLiteralComparison(testDB: TestDB) {
        withTables(testDB, TableWithDatetime) {
            TableWithDatetime.insert {
                it[datetime] = defaultDatetime
            }
            val query = TableWithDatetime.selectAll()
                .where {
                    TableWithDatetime.datetime less dateTimeLiteral(futureDatetime)
                }
            query.firstOrNull().shouldNotBeNull()
        }
    }

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
}
