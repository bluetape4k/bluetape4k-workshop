package io.bluetape4k.workshop.exposed.sql.javatime

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.H2_PSQL
import io.bluetape4k.workshop.exposed.TestDB.MYSQL_V8
import io.bluetape4k.workshop.exposed.TestDB.POSTGRESQL
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.sql.Cast
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.castTo
import org.jetbrains.exposed.sql.get
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.javatime.CurrentDate
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.dateParam
import org.jetbrains.exposed.sql.javatime.dateTimeParam
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.day
import org.jetbrains.exposed.sql.javatime.hour
import org.jetbrains.exposed.sql.javatime.minute
import org.jetbrains.exposed.sql.javatime.month
import org.jetbrains.exposed.sql.javatime.second
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timeLiteral
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.year
import org.jetbrains.exposed.sql.json.extract
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.slice
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MariaDBDialect
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.OracleDialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MILLIS
import java.time.temporal.Temporal
import java.util.*

class JavaTimeTest: AbstractExposedTest() {

    companion object: KLogging()

    private val timestampWithTimeZoneUnsupportedDB = setOf(TestDB.MYSQL_V5) // + TestDB.ALL_MARIADB

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `javaTime functions`(testDB: TestDB) {
        // FIXME: MySQL_V8 에서는 LocalDate 에 대해 Year 함수가 제대로 동작하지 않는다
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V8 }

        withTables(testDB, CitiesTime) {
            val now = LocalDateTime.now()

            /**
             * Insert a city with local time
             *
             * H2:
             * ```sql
             * INSERT INTO CITIESTIME ("name", LOCAL_TIME) VALUES ('Seoul', '2025-01-17T09:21:19.842087')
             * ```
             */
            val cityID = CitiesTime.insertAndGetId {
                it[name] = "Seoul"
                it[local_time] = now
            }

            val row = CitiesTime
                .select(
                    CitiesTime.local_time.year(),
                    CitiesTime.local_time.month(),
                    CitiesTime.local_time.day(),
                    CitiesTime.local_time.hour(),
                    CitiesTime.local_time.minute(),
                    CitiesTime.local_time.second(),
                )
                .where { CitiesTime.id eq cityID }
                .single()

            row[CitiesTime.local_time.year()] shouldBeEqualTo now.year
            row[CitiesTime.local_time.month()] shouldBeEqualTo now.month.value
            row[CitiesTime.local_time.day()] shouldBeEqualTo now.dayOfMonth
            row[CitiesTime.local_time.hour()] shouldBeEqualTo now.hour
            row[CitiesTime.local_time.minute()] shouldBeEqualTo now.minute
            row[CitiesTime.local_time.second()] shouldBeEqualTo now.second
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `selecting instant using expression`(testDB: TestDB) {
        val testTable = object: Table("ts_table") {
            val ts = timestamp("ts")
            val tsn = timestamp("tsn").nullable()
        }

        val now = Instant.now()

        withTables(testDB, testTable) {
            testTable.insert {
                it[ts] = now
                it[tsn] = now
            }

            /**
             * select max timestamp
             *
             * ```sql
             * SELECT MAX(TS_TABLE.TS) FROM TS_TABLE
             * ```
             *
             * select min timestamp
             *
             * ```sql
             * SELECT MIN(TS_TABLE.TS) FROM TS_TABLE
             * ```
             *
             */
            val maxTsExpr = testTable.ts.max()
            val maxTimestamp = testTable.select(maxTsExpr).single()[maxTsExpr]
            maxTimestamp shouldBeEqualTo now

            val minTsExpr = testTable.ts.min()
            val minTimestamp = testTable.select(minTsExpr).single()[minTsExpr]
            minTimestamp shouldBeEqualTo now

            val maxTsnExpr = testTable.tsn.max()
            val maxTimestampNullable = testTable.select(maxTsnExpr).single()[maxTsnExpr]
            maxTimestampNullable shouldBeEqualTo now

            val minTsnExpr = testTable.tsn.min()
            val minTimestampNullable = testTable.select(minTsnExpr).single()[minTsnExpr]
            minTimestampNullable shouldBeEqualTo now
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Storing LocalDateTime with nanos`(testDB: TestDB) {
        // Assumptions.assumeTrue { testDB != TestDB.H2_PSQL }

        /**
         * Table to store LocalDateTime with nanos
         *
         * ```sql
         * CREATE TABLE IF NOT EXISTS TESTLOCALDATETIME (
         *      ID INT AUTO_INCREMENT PRIMARY KEY,
         *      "time" DATETIME(9) NOT NULL
         * )
         * ```
         */
        val testDate = object: IntIdTable("TestLocalDateTime") {
            val time: Column<LocalDateTime> = datetime("time")
        }

        withTables(testDB, testDate) {
            val dateTime = LocalDateTime.now()
            val nanos = 111111

            /**
             * insert 2 separate nanosecond constants to ensure test's rounding mode matches DB precision
             *
             * H2:
             * ```sql
             * INSERT INTO TESTLOCALDATETIME ("time") VALUES ('2025-01-26T14:14:43.000111111')
             * INSERT INTO TESTLOCALDATETIME ("time") VALUES ('2025-01-26T14:14:43.000111118')
             * ```
             */
            val dateTimeWithFewNanos = dateTime.withNano(nanos)
            val dateTimeWithManyNanos = dateTime.withNano(nanos + 7)

            testDate.insert {
                it[time] = dateTimeWithFewNanos
            }

            testDate.insert {
                it[time] = dateTimeWithManyNanos
            }

            val dateTimesFromDB = testDate.selectAll().map { it[testDate.time] }
            dateTimesFromDB[0] shouldTemporalEqualTo dateTimeWithFewNanos
            dateTimesFromDB[1] shouldTemporalEqualTo dateTimeWithManyNanos
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `LocalDate comparison`(testDB: TestDB) {
        val testTable = object: Table("test_table") {
            val created = date("created")
            val deleted = date("deleted")
        }

        withTables(testDB, testTable) {
            val mayTheFourth = LocalDate.of(2024, 5, 4)
            testTable.insert {
                it[created] = mayTheFourth
                it[deleted] = mayTheFourth
            }
            testTable.insert {
                it[created] = mayTheFourth
                it[deleted] = mayTheFourth.plusDays(1L)
            }

            val sameDateResult = testTable.selectAll()
                .where { testTable.created eq testTable.deleted }.toList()
            sameDateResult shouldHaveSize 1
            sameDateResult.single()[testTable.deleted] shouldBeEqualTo mayTheFourth

            val sameMonthResult = testTable.selectAll()
                .where { testTable.created.month() eq testTable.deleted.month() }
                .toList()
            sameMonthResult shouldHaveSize 2

            /**
             * MySQL_V8:
             * ```sql
             * SELECT test_table.created, test_table.deleted
             *   FROM test_table
             *  WHERE YEAR(test_table.created) = YEAR('2024-05-04')
             * ```
             *
             * Postgres:
             * ```sql
             * SELECT test_table.created, test_table.deleted
             *   FROM test_table
             *  WHERE Extract(YEAR FROM test_table.created) = Extract(YEAR FROM CAST('2024-05-04' AS DATE))
             * ```
             */
            val year2024 = if (currentDialect is PostgreSQLDialect) {
                // PostgreSQL requires explicit type cast to resolve function date_part
                dateParam(mayTheFourth).castTo(JavaLocalDateColumnType()).year()
            } else {
                dateParam(mayTheFourth).year()
            }

            val createdIn2025 = testTable.selectAll()
                .where { testTable.created.year() eq year2024 }
                .toList()
            createdIn2025 shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `LocalDateTime comparison`(testDB: TestDB) {
        val tester = object: IntIdTable("test_table_dt") {
            val created = datetime("created")
            val modified = datetime("modified")
        }

        withTables(testDB, tester) {
            val mayTheFourth = LocalDateTime.of(
                2024, 5, 4,
                13, 0, 21,
                871130789
            )
            val now = LocalDateTime.now()

            val id1 = tester.insertAndGetId {
                it[created] = mayTheFourth
                it[modified] = mayTheFourth
            }
            val id2 = tester.insertAndGetId {
                it[created] = mayTheFourth
                it[modified] = now
            }

            // these DB take the nanosecond value 871_130_789 and round up to default precision (e.g. in Oracle: 871_131)
            val requiresExplicitDTCast = listOf(H2_PSQL)
            val dateTime = when (testDB) {
                in requiresExplicitDTCast -> Cast(dateTimeParam(mayTheFourth), JavaLocalDateTimeColumnType())
                else -> dateTimeParam(mayTheFourth)
            }

            val createdMayFourth = tester.selectAll()
                .where { tester.created eq dateTime }
                .count()
            createdMayFourth shouldBeEqualTo 2L

            val modifiedAtSame = tester.selectAll()
                .where { tester.modified eq tester.created }
                .single()
            modifiedAtSame[tester.id] shouldBeEqualTo id1

            val modifiedAtLater = tester.selectAll()
                .where { tester.modified greater tester.created }
                .single()
            modifiedAtLater[tester.id] shouldBeEqualTo id2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `DateTime as JsonB`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2 }

        val tester = object: IntIdTable("tester") {
            val created = datetime("created")
            val modified = jsonb<ModifierData>("modified", Json.Default)
        }

        withTables(testDB, tester) {
            val dateTimeNow = LocalDateTime.now()

            val id1 = tester.insert {
                it[created] = dateTimeNow.minusYears(1)
                it[modified] = ModifierData(1, dateTimeNow)
            }
            val id2 = tester.insert {
                it[created] = dateTimeNow.plusYears(1)
                it[modified] = ModifierData(2, dateTimeNow)
            }

            val prefix = if (currentDialectTest is PostgreSQLDialect) "" else "."

            // value extracted in same manner it is stored, a json string
            val modifiedAsString = tester.modified.extract<String>("${prefix}timestamp")
            val allModifiedAsString = tester.select(modifiedAsString)
            allModifiedAsString.all { it[modifiedAsString] == dateTimeNow.toString() }.shouldBeTrue()

            // PostgreSQL requires explicit type cast to timestamp for in-DB comparison
            val dateModified = when (currentDialectTest) {
                is PostgreSQLDialect -> tester.modified.extract<String>("${prefix}timestamp")
                    .castTo(JavaLocalDateTimeColumnType())

                else -> tester.modified.extract<String>("${prefix}timestamp")
            }
            val modifiedBeforeCreation = tester.selectAll()
                .where { dateModified less tester.created }
                .single()
            modifiedBeforeCreation[tester.modified].userId shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Timestamp with TimeZone`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in timestampWithTimeZoneUnsupportedDB }
        val tester = object: IntIdTable("tester") {
            val timestampWithTimeZone = timestampWithTimeZone("timestampz_column")
        }

        withTables(testDB, tester) {
            // Africa/Cairo time zone
            TimeZone.setDefault(TimeZone.getTimeZone("Africa/Cairo"))
            ZoneId.systemDefault().id shouldBeEqualTo "Africa/Cairo"

            val cairoNow = OffsetDateTime.now(ZoneId.systemDefault())

            val cairoId = tester.insertAndGetId {
                it[timestampWithTimeZone] = cairoNow
            }

            val cairoNowInsertedInCairoTimeZone = tester.selectAll()
                .where { tester.id eq cairoId }
                .single()[tester.timestampWithTimeZone]

            // UTC time zone
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))
            ZoneId.systemDefault().id shouldBeEqualTo "UTC"

            val cairoNowRetrievedInUTCTimeZone = tester.selectAll()
                .where { tester.id eq cairoId }
                .single()[tester.timestampWithTimeZone]

            val utcID = tester.insertAndGetId {
                it[timestampWithTimeZone] = cairoNow
            }

            val cairoNowInsertedInUTCTimeZone = tester.selectAll()
                .where { tester.id eq utcID }
                .single()[tester.timestampWithTimeZone]


            // Seoul time zone
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            ZoneId.systemDefault().id shouldBeEqualTo "Asia/Seoul"

            val cairoNowRetrievedInSeoulTimeZone = tester.selectAll()
                .where { tester.id eq cairoId }
                .single()[tester.timestampWithTimeZone]

            val seoulID = tester.insertAndGetId {
                it[timestampWithTimeZone] = cairoNow
            }

            val cairoNowInsertedInSeoulTimeZone = tester.selectAll()
                .where { tester.id eq seoulID }
                .single()[tester.timestampWithTimeZone]

            // PostgreSQL and MySQL always store the timestamp in UTC, thereby losing the original time zone.
            // To preserve the original time zone, store the time zone information in a separate column.
            val isOriginalTimeZonePreserved = testDB !in (TestDB.ALL_MYSQL + TestDB.ALL_POSTGRES)
            if (isOriginalTimeZonePreserved) {
                // Assert that time zone is preserved when the same value is inserted in different time zones
                cairoNowInsertedInCairoTimeZone shouldTemporalEqualTo cairoNow
                cairoNowInsertedInUTCTimeZone shouldTemporalEqualTo cairoNow
                cairoNowInsertedInSeoulTimeZone shouldTemporalEqualTo cairoNow

                // Assert that time zone is preserved when the same record is retrieved in different time zones
                cairoNowRetrievedInUTCTimeZone shouldTemporalEqualTo cairoNow
                cairoNowRetrievedInSeoulTimeZone shouldTemporalEqualTo cairoNow
            } else {
                // Assert equivalence in UTC when the same value is inserted in different time zones
                cairoNowInsertedInUTCTimeZone shouldTemporalEqualTo cairoNowInsertedInCairoTimeZone
                cairoNowInsertedInSeoulTimeZone shouldTemporalEqualTo cairoNowInsertedInUTCTimeZone

                // Assert equivalence in UTC when the same record is retrieved in different time zones
                cairoNowRetrievedInSeoulTimeZone shouldTemporalEqualTo cairoNowRetrievedInUTCTimeZone
            }

            // Reset to original time zone as set up in DatabaseTestsBase init block
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))
            ZoneId.systemDefault().id shouldBeEqualTo "UTC"
        }
    }

    @ParameterizedTest
    // @FieldSource("timestampWithTimeZoneUnsupportedDB")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Timestamp With TimeZone Throws Exception For Unsupported Dialects`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in timestampWithTimeZoneUnsupportedDB }

        val tester = object: IntIdTable("tester") {
            val timestampWithTimeZone = timestampWithTimeZone("timestampz_column")
        }
        withDb(testDB) {
            expectException<UnsupportedByDialectException> {
                SchemaUtils.create(tester)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Timestamp with TimeZone extension functions`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (timestampWithTimeZoneUnsupportedDB + TestDB.ALL_H2_V1) }

        val tester = object: IntIdTable("tester") {
            val timestampWithTimeZone = timestampWithTimeZone("timestampz_column")
        }

        withTables(testDB, tester) {
            // UTC time zone
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))
            ZoneId.systemDefault().id shouldBeEqualTo "UTC"

            val nowText = "2023-05-04T05:04:01.123123123+00:00"

            val now: OffsetDateTime = OffsetDateTime.parse(nowText)
            val nowId = tester.insertAndGetId {
                it[timestampWithTimeZone] = now
            }

            tester.select(tester.timestampWithTimeZone.date())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.date()] shouldBeEqualTo now.toLocalDate()

            val expectedTime: LocalTime = when (testDB) {
                MYSQL_V8, in TestDB.ALL_POSTGRES_LIKE,
                    -> OffsetDateTime.parse("2023-05-04T05:04:01.123123+00:00")  // NOTE: Microseconds 까지만 지원
                else -> now
            }.toLocalTime()

            tester.select(tester.timestampWithTimeZone.time())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.time()] shouldBeEqualTo expectedTime

            tester.select(tester.timestampWithTimeZone.month())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.month()] shouldBeEqualTo now.month.value

            tester.select(tester.timestampWithTimeZone.day())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.day()] shouldBeEqualTo now.dayOfMonth

            tester.select(tester.timestampWithTimeZone.hour())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.hour()] shouldBeEqualTo now.hour

            tester.select(tester.timestampWithTimeZone.minute())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.minute()] shouldBeEqualTo now.minute

            tester.select(tester.timestampWithTimeZone.second())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.second()] shouldBeEqualTo now.second
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `current DateTime function`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val fakeTestTable = object: IntIdTable("fakeTable") {}

        withTables(testDB, fakeTestTable) {
            fun currentDbDateTime(): LocalDateTime {
                return fakeTestTable.select(CurrentDateTime).first()[CurrentDateTime]
            }

            // 레코드가 없으면 조회가 실패한다.
            fakeTestTable.insert {}

            currentDbDateTime()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `DateTime as Array`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in setOf(TestDB.POSTGRESQL, TestDB.H2) }

        val defaultDates = listOf(today)
        val defaultDateTimes = listOf(LocalDateTime.now())
        val tester = object: Table("array_tester") {
            val dates = array("dates", JavaLocalDateColumnType()).default(defaultDates)
            val datetimes = array("datetimes", JavaLocalDateTimeColumnType()).default(defaultDateTimes)
        }

        withTables(testDB, tester) {
            tester.insert { }
            val result1 = tester.selectAll().single()
            log.debug { "datetimes=${result1[tester.datetimes]}" }
            result1[tester.dates] shouldBeEqualTo defaultDates
            if (testDB == POSTGRESQL) {
                result1[tester.datetimes] shouldBeEqualTo defaultDateTimes.map { it.truncatedTo(MILLIS) }
            } else {
                result1[tester.datetimes] shouldBeEqualTo defaultDateTimes
            }

            /**
             * H2:
             * ```sql
             * INSERT INTO ARRAY_TESTER (DATES, "dateTimes")
             * VALUES (
             *      ARRAY ['2020-05-04','2021-05-04','2022-05-04'],
             *      ARRAY ['2020-05-04T09:09:09','2021-05-04T09:09:09','2022-05-04T09:09:09']
             * )
             * ```
             */
            val datesInput = List(3) { LocalDate.of(2020 + it, 5, 4) }
            val datetimeInput = List(3) { LocalDateTime.of(2020 + it, 5, 4, 9, 9, 9) }
            tester.insert {
                it[tester.dates] = datesInput
                it[tester.datetimes] = datetimeInput
            }

            /**
             * H2:
             * ```sql
             * SELECT ARRAY_TESTER.DATES[3],
             *        ARRAY_SLICE(ARRAY_TESTER."dateTimes",1,2)
             *   FROM ARRAY_TESTER
             *  WHERE YEAR(ARRAY_TESTER.DATES[1]) = 2020
             * ```
             */
            val lastDate = tester.dates[3]
            val firstTwoDatetimes = tester.datetimes.slice(1, 2)
            val result2 = tester.select(lastDate, firstTwoDatetimes)
                .where { tester.dates[1].year() eq 2020 }
                .single()

            result2[lastDate] shouldBeEqualTo datesInput.last()
            result2[firstTwoDatetimes] shouldBeEqualTo datetimeInput.take(2)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select by time literal equality`(testDB: TestDB) {
        val tester = object: IntIdTable("with_time") {
            val time = time("time")
        }
        withTables(testDB, tester) {
            val localTime = LocalTime.of(13, 5)
            val localTimeLiteral = timeLiteral(localTime)

            // UTC time zone
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))
            ZoneId.systemDefault().id shouldBeEqualTo "UTC"

            tester.insert {
                it[time] = localTime
            }

            /**
             * H2:
             * ```sql
             * SELECT TABLEWITHTIME.ID, TABLEWITHTIME."time"
             *   FROM TABLEWITHTIME
             *  WHERE TABLEWITHTIME."time" = '13:05:00'
             * ```
             */
            tester.select(tester.id, tester.time)
                .where { tester.time eq localTimeLiteral }
                .single()[tester.time] shouldBeEqualTo localTime
        }
    }

    /**
     * H2:
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "date" DATE DEFAULT CURRENT_DATE NOT NULL
     * );
     * CREATE INDEX TEST_TABLE_DATE ON TEST_TABLE ("date")
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CurrentDate as default expression`(testDB: TestDB) {
        val tester = object: IntIdTable("test_table") {
            val date: Column<LocalDate> = date("date").index().defaultExpression(CurrentDate)
        }
        withTables(testDB, tester) {
            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()
        }
    }
}

//
// ---------------------------------------------------------------------------------
//

fun <T: Temporal> T?.isEqualDateTime(other: Temporal?): Boolean = try {
    this shouldTemporalEqualTo other
    true
} catch (_: Exception) {
    false
}

infix fun <T: Temporal> T?.shouldTemporalEqualTo(d2: T?) {
    val d1 = this
    when {
        d1 == null && d2 == null -> return
        d1 == null -> error("d1 is null while d2 is not on ${currentDialectTest.name}")
        d2 == null -> error("d1 is not null while d2 is null on ${currentDialectTest.name}")

        d1 is LocalTime && d2 is LocalTime -> {
            d1.toSecondOfDay() shouldBeEqualTo d2.toSecondOfDay()
            // d1 이 DB에서 읽어온 Temporal 값이어야 한다. (nanos 를 지원하는 Dialect 에서만 비교한다)
            if (d1.nano != 0) {
                d1.nano shouldFractionalPartEqualTo d2.nano
            }
        }

        d1 is LocalDateTime && d2 is LocalDateTime -> {
            d1.toEpochSecond(ZoneOffset.UTC) shouldBeEqualTo d2.toEpochSecond(ZoneOffset.UTC)
            d1.nano shouldFractionalPartEqualTo d2.nano
        }

        d1 is Instant && d2 is Instant -> {
            d1.epochSecond shouldBeEqualTo d2.epochSecond
            d1.nano shouldFractionalPartEqualTo d2.nano
        }

        d1 is OffsetDateTime && d2 is OffsetDateTime -> {
            d1.toLocalDateTime() shouldTemporalEqualTo d2.toLocalDateTime()
            d1.offset shouldBeEqualTo d2.offset
        }

        else -> {
            d1 shouldBeEqualTo d2
        }
    }
}

infix fun Int.shouldFractionalPartEqualTo(nano2: Int) {
    val nano1 = this
    val dialect = currentDialectTest

    when (dialect) {
        // accurate to 100 nanoseconds
        is SQLServerDialect ->
            nano1.nanoRoundTo100Nanos() shouldBeEqualTo nano2.nanoRoundTo100Nanos()

        // microsecond
        is MariaDBDialect ->
            nano1.nanoFloorToMicro() shouldBeEqualTo nano2.nanoFloorToMicro()

        is H2Dialect, is PostgreSQLDialect, is MysqlDialect ->
            when ((dialect as? MysqlDialect)?.isFractionDateTimeSupported()) {
                null, true -> {
                    println("nano1: ${nano1.nanoRoundToMicro()}, nano2: ${nano2.nanoRoundToMicro()}")
                    nano1.nanoRoundToMicro() shouldBeEqualTo nano2.nanoRoundToMicro()
                }

                else -> {} // don't compare fractional part
            }

        // milliseconds
        is OracleDialect ->
            nano1.nanoRoundToMilli() shouldBeEqualTo nano2.nanoRoundToMilli()

        is SQLiteDialect ->
            nano1.nanoFloorToMilli() shouldBeEqualTo nano2.nanoFloorToMilli()

        else ->
            error("Unsupported dialect ${dialect.name}")
    }
}

fun Int.nanoRoundTo100Nanos(): Int =
    this.toBigDecimal().divide(100.toBigDecimal(), RoundingMode.HALF_UP).toInt()

fun Int.nanoRoundToMicro(): Int =
    this.toBigDecimal().divide(1_000.toBigDecimal(), RoundingMode.HALF_UP).toInt()

fun Int.nanoRoundToMilli(): Int =
    this.toBigDecimal().divide(1_000_000.toBigDecimal(), RoundingMode.HALF_UP).toInt()

fun Int.nanoFloorToMicro(): Int = this / 1_000

fun Int.nanoFloorToMilli(): Int = this / 1_000_000


val today: LocalDate = LocalDate.now()

object CitiesTime: IntIdTable("CitiesTime") {
    val name: Column<String> = varchar("name", 50)
    val local_time: Column<LocalDateTime?> = datetime("local_time").nullable()
}

@Serializable
data class ModifierData(
    val userId: Int,
    @Serializable(with = DateTimeSerializer::class)
    val timestamp: LocalDateTime,
)

object DateTimeSerializer: KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString())
    }
}
