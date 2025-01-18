package io.bluetape4k.workshop.exposed.sql.kotlin.datetime

import MigrationUtils
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.sql.Cast
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.castTo
import org.jetbrains.exposed.sql.get
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.json.extract
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDate
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinLocalDateColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinLocalDateTimeColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.dateParam
import org.jetbrains.exposed.sql.kotlin.datetime.dateTimeParam
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.kotlin.datetime.day
import org.jetbrains.exposed.sql.kotlin.datetime.duration
import org.jetbrains.exposed.sql.kotlin.datetime.hour
import org.jetbrains.exposed.sql.kotlin.datetime.minute
import org.jetbrains.exposed.sql.kotlin.datetime.month
import org.jetbrains.exposed.sql.kotlin.datetime.second
import org.jetbrains.exposed.sql.kotlin.datetime.time
import org.jetbrains.exposed.sql.kotlin.datetime.timeLiteral
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.year
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
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.time.Duration

class KotlinTimeTest: AbstractExposedTest() {

    private val timestampWithTimeZoneUnsupportedDB = setOf(TestDB.MYSQL_V5) // TestDB.ALL_MARIADB + TestDB.MYSQL_V5

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun kotlinTimeFunctions(testDB: TestDB) {
        // MYSQL V8 에서 year(), month(), day(), hour(), minute(), second() functions 이 제대로 작동하지 않는다.
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V8 }

        withTables(testDB, CitiesTime) {
            val now: LocalDateTime = now()

            val cityID = CitiesTime.insertAndGetId {
                it[name] = "Tunisia"
                it[local_time] = now
            }

            CitiesTime.selectAll().single()[CitiesTime.local_time] shouldDateTimeEqualTo now

            val insertedYear = CitiesTime.select(CitiesTime.local_time.year())
                .where { CitiesTime.id.eq(cityID) }
                .single()[CitiesTime.local_time.year()]
            val insertedMonth = CitiesTime.select(CitiesTime.local_time.month())
                .where { CitiesTime.id.eq(cityID) }
                .single()[CitiesTime.local_time.month()]
            val insertedDay = CitiesTime.select(CitiesTime.local_time.day())
                .where { CitiesTime.id.eq(cityID) }
                .single()[CitiesTime.local_time.day()]
            val insertedHour = CitiesTime.select(CitiesTime.local_time.hour())
                .where { CitiesTime.id.eq(cityID) }
                .single()[CitiesTime.local_time.hour()]
            val insertedMinute = CitiesTime.select(CitiesTime.local_time.minute())
                .where { CitiesTime.id.eq(cityID) }
                .single()[CitiesTime.local_time.minute()]
            val insertedSecond = CitiesTime.select(CitiesTime.local_time.second())
                .where { CitiesTime.id.eq(cityID) }
                .single()[CitiesTime.local_time.second()]

            insertedYear shouldBeEqualTo now.year
            insertedMonth shouldBeEqualTo now.month.value
            insertedDay shouldBeEqualTo now.dayOfMonth
            insertedHour shouldBeEqualTo now.hour
            insertedMinute shouldBeEqualTo now.minute
            insertedSecond shouldBeEqualTo now.second
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testStoringLocalDateTimeWithNanos(testDB: TestDB) {
        val testDate = object: IntIdTable("TestLocalDateTime") {
            val time = datetime("time")
        }

        withTables(testDB, testDate) {
            val dateTime = Instant.parse("2023-05-04T05:04:00.000Z") // has 0 nanoseconds
            val nanos = DateTimeUnit.NANOSECOND * 111111
            // insert 2 separate constants to ensure test's rounding mode matches DB precision
            val dateTimeWithFewNanos = dateTime.plus(1, nanos).toLocalDateTime(TimeZone.currentSystemDefault())
            val dateTimeWithManyNanos = dateTime.plus(7, nanos).toLocalDateTime(TimeZone.currentSystemDefault())
            testDate.insert {
                it[testDate.time] = dateTimeWithFewNanos
            }
            testDate.insert {
                it[testDate.time] = dateTimeWithManyNanos
            }

            val dateTimesFromDB = testDate.selectAll().map { it[testDate.time] }

            dateTimesFromDB[0] shouldDateTimeEqualTo dateTimeWithFewNanos
            dateTimesFromDB[1] shouldDateTimeEqualTo dateTimeWithManyNanos
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `test selecting Instant using expressions`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val ts = timestamp("ts")
            val tsn = timestamp("tsn").nullable()
        }

        val now = Clock.System.now()

        withTables(testDB, tester) {
            tester.insert {
                it[ts] = now
                it[tsn] = now
            }

            val maxTsExpr = tester.ts.max()
            val maxTimestamp = tester.select(maxTsExpr).single()[maxTsExpr]
            maxTimestamp shouldDateTimeEqualTo now

            val minTsExpr = tester.ts.min()
            val minTimestamp = tester.select(minTsExpr).single()[minTsExpr]
            minTimestamp shouldDateTimeEqualTo now

            val maxTsnExpr = tester.tsn.max()
            val maxNullableTimestamp = tester.select(maxTsnExpr).single()[maxTsnExpr]
            maxNullableTimestamp shouldDateTimeEqualTo now

            val minTsnExpr = tester.tsn.min()
            val minNullableTimestamp = tester.select(minTsnExpr).single()[minTsnExpr]
            minNullableTimestamp shouldDateTimeEqualTo now
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testLocalDateComparison(testDB: TestDB) {
        val tester = object: Table("test_table") {
            val created = date("created")
            val deleted = date("deleted")
        }

        withTables(testDB, tester) {
            val mayTheFourth = LocalDate(2023, 5, 4)

            tester.insert {
                it[created] = mayTheFourth
                it[deleted] = mayTheFourth
            }
            tester.insert {
                it[created] = mayTheFourth
                it[deleted] = mayTheFourth.plus(1, DateTimeUnit.DAY)
            }

            val sameDateResult = tester.selectAll().where { tester.created eq tester.deleted }.toList()
            sameDateResult shouldHaveSize 1
            sameDateResult.single()[tester.deleted] shouldBeEqualTo mayTheFourth

            val sameMonthResult = tester.selectAll()
                .where { tester.created.month() eq tester.deleted.month() }
                .toList()
            sameMonthResult shouldHaveSize 2

            val year2023 = if (currentDialectTest is PostgreSQLDialect) {
                // PostgreSQL requires explicit type cast to resolve function date_part
                dateParam(mayTheFourth).castTo(KotlinLocalDateColumnType()).year()
            } else {
                dateParam(mayTheFourth).year()
            }
            val createdIn2023 = tester.selectAll()
                .where { tester.created.year() eq year2023 }
                .toList()

            createdIn2023 shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testLocalDateTimeComparison(testDB: TestDB) {
        val tester = object: IntIdTable("test_table_dt") {
            val created = datetime("created")
            val modified = datetime("modified")
        }

        withTables(testDB, tester) {
            val mayTheFourth = "2011-05-04T13:00:21.871130789Z"
            val mayTheFourthDT = Instant.parse(mayTheFourth).toLocalDateTime(TimeZone.currentSystemDefault())
            val nowDT = now()
            val id1 = tester.insertAndGetId {
                it[created] = mayTheFourthDT
                it[modified] = mayTheFourthDT
            }
            val id2 = tester.insertAndGetId {
                it[created] = mayTheFourthDT
                it[modified] = nowDT
            }

            // these DB take the nanosecond value 871_130_789 and round up to default precision (e.g. in Oracle: 871_131)
//            val requiresExplicitDTCast =
//                listOf(TestDB.ORACLE, TestDB.H2_V2_ORACLE, TestDB.H2_V2_PSQL, TestDB.H2_V2_SQLSERVER)
            val requiresExplicitDTCast = listOf(TestDB.H2_PSQL)

            val dateTime = when (testDB) {
                in requiresExplicitDTCast -> Cast(dateTimeParam(mayTheFourthDT), KotlinLocalDateTimeColumnType())
                else -> dateTimeParam(mayTheFourthDT)
            }
            val createdMayFourth = tester.selectAll()
                .where { tester.created eq dateTime }
                .count()
            createdMayFourth shouldBeEqualTo 2

            val modifiedAtSameDT = tester.selectAll()
                .where { tester.modified eq tester.created }
                .single()
            modifiedAtSameDT[tester.id] shouldBeEqualTo id1

            val modifiedAtLaterDT = tester.selectAll()
                .where { tester.modified greater tester.created }
                .single()
            modifiedAtLaterDT[tester.id] shouldBeEqualTo id2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDateTimeAsJsonB(testDB: TestDB) {
        val tester = object: Table("tester") {
            val created = datetime("created")
            val modified = jsonb<ModifierData>("modified", Json.Default)
        }

        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2 }

        withTables(testDB, tester) {
            val dateTimeNow = now()

            tester.insert {
                it[created] = dateTimeNow.date.minus(1, DateTimeUnit.YEAR).atTime(0, 0, 0)
                it[modified] = ModifierData(1, dateTimeNow)
            }
            tester.insert {
                it[created] = dateTimeNow.date.plus(1, DateTimeUnit.YEAR).atTime(0, 0, 0)
                it[modified] = ModifierData(2, dateTimeNow)
            }

            val prefix = if (currentDialectTest is PostgreSQLDialect) "" else "."

            // value extracted in same manner it is stored, a json string
            val modifiedAsString = tester.modified.extract<String>("${prefix}timestamp")
            val allModifiedAsString = tester.select(modifiedAsString)
            allModifiedAsString.all { it[modifiedAsString] == dateTimeNow.toString() }.shouldBeTrue()

            // value extracted as json, with implicit LocalDateTime serializer() performing conversions
            val modifiedAsJson = tester.modified.extract<LocalDateTime>("${prefix}timestamp", toScalar = false)
            val allModifiedAsJson = tester.select(modifiedAsJson)
            allModifiedAsJson.all { it[modifiedAsJson] == dateTimeNow }.shouldBeTrue()

            // PostgreSQL requires explicit type cast to timestamp for in-DB comparison
            val dateModified = if (currentDialectTest is PostgreSQLDialect) {
                tester.modified.extract<LocalDateTime>("${prefix}timestamp").castTo(KotlinLocalDateTimeColumnType())
            } else {
                tester.modified.extract<LocalDateTime>("${prefix}timestamp")
            }
            val modifiedBeforeCreation = tester.selectAll().where { dateModified less tester.created }.single()
            modifiedBeforeCreation[tester.modified].userId shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testTimestampWithTimeZone(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in timestampWithTimeZoneUnsupportedDB }

        val tester = object: IntIdTable("TestTable") {
            val timestampWithTimeZone = timestampWithTimeZone("timestamptz-column")
        }

        withTables(testDB, tester) {
            // Cairo time zone
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Africa/Cairo"))
            ZoneId.systemDefault().id shouldBeEqualTo "Africa/Cairo"

            val cairoNow = OffsetDateTime.now(ZoneId.systemDefault())
            val cairoId = tester.insertAndGetId {
                it[timestampWithTimeZone] = cairoNow
            }

            val cairoNowInsertedInCairoTimeZone = tester.selectAll()
                .where { tester.id eq cairoId }
                .single()[tester.timestampWithTimeZone]

            // UTC time zone
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.UTC))
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

            // Tokyo time zone
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            ZoneId.systemDefault().id shouldBeEqualTo "Asia/Tokyo"

            val cairoNowRetrievedInTokyoTimeZone = tester.selectAll()
                .where { tester.id eq cairoId }
                .single()[tester.timestampWithTimeZone]

            val tokyoID = tester.insertAndGetId {
                it[timestampWithTimeZone] = cairoNow
            }

            val cairoNowInsertedInTokyoTimeZone = tester.selectAll()
                .where { tester.id eq tokyoID }
                .single()[tester.timestampWithTimeZone]

            // PostgreSQL and MySQL always store the timestamp in UTC, thereby losing the original time zone.
            // To preserve the original time zone, store the time zone information in a separate column.
            val isOriginalTimeZonePreserved = testDB !in (TestDB.ALL_MYSQL + TestDB.ALL_POSTGRES)
            if (isOriginalTimeZonePreserved) {
                // Assert that time zone is preserved when the same value is inserted in different time zones
                cairoNowInsertedInCairoTimeZone shouldDateTimeEqualTo cairoNow
                cairoNowInsertedInUTCTimeZone shouldDateTimeEqualTo cairoNow
                cairoNowInsertedInTokyoTimeZone shouldDateTimeEqualTo cairoNow

                // Assert that time zone is preserved when the same record is retrieved in different time zones
                cairoNowRetrievedInUTCTimeZone shouldDateTimeEqualTo cairoNow
                cairoNowRetrievedInTokyoTimeZone shouldDateTimeEqualTo cairoNow
            } else {
                // Assert equivalence in UTC when the same value is inserted in different time zones
                cairoNowInsertedInUTCTimeZone shouldDateTimeEqualTo cairoNowInsertedInCairoTimeZone
                cairoNowInsertedInTokyoTimeZone shouldDateTimeEqualTo cairoNowInsertedInUTCTimeZone

                // Assert equivalence in UTC when the same record is retrieved in different time zones
                cairoNowRetrievedInTokyoTimeZone shouldDateTimeEqualTo cairoNowRetrievedInUTCTimeZone
            }

            // Reset to original time zone as set up in DatabaseTestsBase init block
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.UTC))
            ZoneId.systemDefault().id shouldBeEqualTo "UTC"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testTimestampWithTimeZoneThrowsExceptionForUnsupportedDialects(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in timestampWithTimeZoneUnsupportedDB }
        val testTable = object: IntIdTable("TestTable") {
            val timestampWithTimeZone = timestampWithTimeZone("timestamptz-column")
        }

        withDb(testDB) {
            expectException<UnsupportedByDialectException> {
                SchemaUtils.create(testTable)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testTimestampWithTimeZoneExtensionFunctions(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (timestampWithTimeZoneUnsupportedDB + TestDB.ALL_H2_V1 + TestDB.MYSQL_V8) }

        val tester = object: IntIdTable("TestTable") {
            val timestampWithTimeZone = timestampWithTimeZone("timestamptz-column")
        }

        withTables(testDB, tester) {
            // UTC time zone
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.UTC))
            ZoneId.systemDefault().id shouldBeEqualTo "UTC"

            val now = OffsetDateTime.parse("2023-05-04T05:04:01.123123123+00:00")
            val nowId = tester.insertAndGetId {
                it[tester.timestampWithTimeZone] = now
            }

            tester.select(tester.timestampWithTimeZone.date())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.date()] shouldBeEqualTo now.toLocalDate().toKotlinLocalDate()

            val expectedTime =
                when (testDB) {
                    // TestDB.SQLITE -> OffsetDateTime.parse("2023-05-04T05:04:01.123+00:00")
                    TestDB.MYSQL_V8, // TestDB.SQLSERVER,
                        // in TestDB.ALL_ORACLE_LIKE,
                    in TestDB.ALL_POSTGRES_LIKE,
                        -> OffsetDateTime.parse("2023-05-04T05:04:01.123123+00:00")
                    else -> now
                }.toLocalTime().toKotlinLocalTime()

            tester.select(tester.timestampWithTimeZone.time())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.time()] shouldBeEqualTo expectedTime

            tester.select(tester.timestampWithTimeZone.year())
                .where { tester.id eq nowId }
                .single()[tester.timestampWithTimeZone.year()] shouldBeEqualTo now.year

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
    fun testCurrentDateTimeFunction(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val fakeTestTable = object: IntIdTable("fakeTable") {}

        withTables(testDB, fakeTestTable) {
            fun currentDbDateTime(): LocalDateTime {
                return fakeTestTable.select(CurrentDateTime).first()[CurrentDateTime]
            }

            fakeTestTable.insert {}

            currentDbDateTime()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testInfiniteDuration(testDB: TestDB) {
        val tester = object: Table("tester") {
            val duration = duration("duration")
        }
        withTables(testDB, tester) {
            tester.insert {
                it[duration] = Duration.INFINITE
            }
            val row = tester.selectAll()
                .where { tester.duration eq Duration.INFINITE }
                .single()
            row[tester.duration] shouldBeEqualTo Duration.INFINITE
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDateTimeAsArray(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in setOf(TestDB.H2, TestDB.POSTGRESQL) }

        val defaultDates = listOf(now().date)
        val defaultDateTimes = listOf(now())
        val tester = object: Table("array_tester") {
            val dates = array("dates", KotlinLocalDateColumnType()).default(defaultDates)
            val datetimes = array("datetimes", KotlinLocalDateTimeColumnType()).default(defaultDateTimes)
        }

        withTables(testDB, tester) {
            tester.insert { }
            val result1 = tester.selectAll().single()
            result1[tester.dates] shouldBeEqualTo defaultDates
            if (testDB == TestDB.POSTGRESQL) {
                result1[tester.datetimes] shouldBeEqualTo defaultDateTimes
                    .map { it.toJavaLocalDateTime().truncatedTo(ChronoUnit.MILLIS).toKotlinLocalDateTime() }
            } else {
                result1[tester.datetimes] shouldBeEqualTo defaultDateTimes
            }

            val datesInput = List(3) { LocalDate(2020 + it, 5, 4) }
            val datetimeInput = List(3) {
                LocalDateTime(2020 + it, 5, 4, 9, 9, 9)
            }
            tester.insert {
                it[dates] = datesInput
                it[datetimes] = datetimeInput
            }

            val lastDate = tester.dates[3]
            val firstTwoDatetimes = tester.datetimes.slice(1, 2)
            val result2 = tester.select(lastDate, firstTwoDatetimes)
                .where { tester.dates[1].year() eq 2020 }
                .single()
            result2[lastDate] shouldDateTimeEqualTo datesInput.last()
            result2[firstTwoDatetimes] shouldBeEqualTo datetimeInput.take(2)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testSelectByTimeLiteralEquality(testDB: TestDB) {
        val tableWithTime = object: IntIdTable("TableWithTime") {
            val time = time("time")
        }
        withTables(testDB, tableWithTime) {
            val localTime = LocalTime(13, 0)
            val localTimeLiteral = timeLiteral(localTime)

            // UTC time zone
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.UTC))
            ZoneId.systemDefault().id shouldBeEqualTo "UTC"

            tableWithTime.insert {
                it[time] = localTime
            }

            tableWithTime.select(tableWithTime.id, tableWithTime.time)
                .where { tableWithTime.time eq localTimeLiteral }
                .single()[tableWithTime.time] shouldBeEqualTo localTime

        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCurrentDateAsDefaultExpression(testDB: TestDB) {
        val testTable = object: LongIdTable("test_table") {
            val date: Column<LocalDate> = date("date").index().defaultExpression(CurrentDate)
        }
        withTables(testDB, testTable) {
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(testTable)
            statements.shouldBeEmpty()
        }
    }
}

infix fun <T> T.shouldDateTimeEqualTo(d2: T?) {
    val d1 = this
    when {
        d1 == null && d2 == null -> return
        d1 == null -> error("d1 is null while d2 is not")
        d2 == null -> error("d1 is not null while d2 is null")
        d1 is LocalTime && d2 is LocalTime -> {
            d1.toSecondOfDay() shouldBeEqualTo d2.toSecondOfDay()
            if (d2.nanosecond != 0) {
                d1.nanosecond shouldFractionalPartEqualTo d2.nanosecond
            }
        }
        d1 is LocalDateTime && d2 is LocalDateTime -> {
            d1.toJavaLocalDateTime().toEpochSecond(ZoneOffset.UTC) shouldBeEqualTo
                    d2.toJavaLocalDateTime().toEpochSecond(ZoneOffset.UTC)
            d1.nanosecond shouldFractionalPartEqualTo d2.nanosecond
        }
        d1 is Instant && d2 is Instant -> {
            d1.epochSeconds shouldBeEqualTo d2.epochSeconds
            d1.nanosecondsOfSecond shouldFractionalPartEqualTo d2.nanosecondsOfSecond
        }
        d1 is OffsetDateTime && d2 is OffsetDateTime -> {
            d1.toLocalDateTime().toKotlinLocalDateTime() shouldDateTimeEqualTo
                    d2.toLocalDateTime().toKotlinLocalDateTime()
            d1.offset shouldBeEqualTo d2.offset
        }
        else -> d1 shouldBeEqualTo d2
    }
}

private infix fun Int.shouldFractionalPartEqualTo(nano2: Int) {
    val nano1 = this
    val dialect = currentDialectTest
    val db = dialect.name
    when (dialect) {
        // accurate to 100 nanoseconds
        is SQLServerDialect ->
            nano1.nanoRoundTo100Nanos() shouldBeEqualTo nano2.nanoRoundTo100Nanos()
        // microseconds
        is MariaDBDialect ->
            nano1.nanoFloorToMicro() shouldBeEqualTo nano2.nanoFloorToMicro()

        is H2Dialect, is PostgreSQLDialect, is MysqlDialect -> {
            when ((dialect as? MysqlDialect)?.isFractionDateTimeSupported()) {
                null, true -> {
                    nano1.nanoRoundToMicro() shouldBeEqualTo nano2.nanoRoundToMicro()
                }
                else -> {} // don't compare fractional part
            }
        }
        // milliseconds
        is OracleDialect ->
            nano1.nanoRoundToMilli() shouldBeEqualTo nano2.nanoRoundToMilli()
        is SQLiteDialect ->
            nano1.nanoFloorToMilli() shouldBeEqualTo nano2.nanoFloorToMilli()
        else -> org.amshove.kluent.fail("Unknown dialect $db")
    }
}

private fun Int.nanoRoundTo100Nanos(): Int =
    this.toBigDecimal().divide(100.toBigDecimal(), RoundingMode.HALF_UP).toInt()

private fun Int.nanoRoundToMicro(): Int =
    this.toBigDecimal().divide(1_000.toBigDecimal(), RoundingMode.HALF_UP).toInt()

private fun Int.nanoRoundToMilli(): Int =
    this.toBigDecimal().divide(1_000_000.toBigDecimal(), RoundingMode.HALF_UP).toInt()

private fun Int.nanoFloorToMicro(): Int = this / 1_000

private fun Int.nanoFloorToMilli(): Int = this / 1_000_000

val today: LocalDate = now().date

object CitiesTime: IntIdTable("CitiesTime") {
    val name = varchar("name", 50) // Column<String>
    val local_time = datetime("local_time").nullable() // Column<datetime>
}

@Serializable
data class ModifierData(val userId: Int, val timestamp: LocalDateTime)
